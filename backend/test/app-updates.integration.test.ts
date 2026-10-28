import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash, randomBytes } from "node:crypto";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { defaultSettings, SettingsService } from "../src/settings/service.js";
import { AppUpdateStore } from "../src/updates/store.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const UPLOAD_TOKEN = "test-only-upload-token-0123456789abcdef";
const APK_TYPE = "application/vnd.android.package-archive";
/** A zip header followed by filler: enough for the server's "is this an APK" check. */
const fakeApk = (bytes = 4096) => Buffer.concat([Buffer.from("PK\u0003\u0004", "latin1"), randomBytes(bytes - 4)]);

describe.skipIf(!TEST_DB_URL)("in-app updates (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let app: FastifyInstance;
  let dir: string;
  const published: number[] = [];

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
    dir = await mkdtemp(join(tmpdir(), "vc-updates-"));
    published.length = 0;
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      settings: new SettingsService(database.prisma, defaultSettings(45)),
      updates: {
        store: new AppUpdateStore(dir),
        uploadToken: UPLOAD_TOKEN,
        onPublished: async (meta) => {
          published.push(meta.versionCode);
        },
      },
      logger: false,
    });
    await app.ready();
  });
  afterEach(async () => {
    await app.close();
    await rm(dir, { recursive: true, force: true });
  });

  const upload = (versionCode: number, body: Buffer, token = UPLOAD_TOKEN, type = APK_TYPE) =>
    app.inject({ method: "PUT", url: `/api/v1/app/apk?versionCode=${versionCode}`, headers: { authorization: `Bearer ${token}`, "content-type": type }, payload: body });

  async function signedInToken(): Promise<string> {
    const { temporaryPassword } = await auth.createUser("alice", { type: "system", id: null });
    const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username: "alice", password: temporaryPassword } })).json();
    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${first.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: "alice brand new passphrase 77" },
    });
    return changed.json().accessToken as string;
  }

  it("publishes an upload, announces it, and serves it only to signed-in users", async () => {
    const before = (await app.inject({ method: "GET", url: "/api/v1/app/version" })).json();
    expect(before).toMatchObject({ latestVersionCode: 0, downloadAvailable: false });

    const apk = fakeApk();
    const r = await upload(5, apk);
    expect(r.statusCode).toBe(200);
    const sha256 = createHash("sha256").update(apk).digest("hex");
    expect(r.json()).toEqual({ versionCode: 5, sha256, size: apk.length });
    expect(published).toEqual([5]);

    const version = (await app.inject({ method: "GET", url: "/api/v1/app/version" })).json();
    expect(version).toEqual({ minSupportedVersionCode: 1, latestVersionCode: 5, latestApkSha256: sha256, downloadAvailable: true });

    expect((await app.inject({ method: "GET", url: "/api/v1/app/apk" })).statusCode).toBe(401);
    const token = await signedInToken();
    const download = await app.inject({ method: "GET", url: "/api/v1/app/apk", headers: { authorization: `Bearer ${token}` } });
    expect(download.statusCode).toBe(200);
    expect(download.headers["content-type"]).toBe(APK_TYPE);
    expect(download.headers["x-apk-sha256"]).toBe(sha256);
    expect(download.rawPayload.equals(apk)).toBe(true);
  });

  it("refuses a wrong token, a non-APK body, the wrong content type and an older version", async () => {
    expect((await upload(5, fakeApk(), "wrong-token-wrong-token-wrong-token-00")).statusCode).toBe(401);
    expect((await upload(5, fakeApk(), "")).statusCode).toBe(401);
    expect((await upload(5, Buffer.alloc(4096, 1))).json().error.code).toBe("VALIDATION_FAILED");
    expect((await upload(5, fakeApk(), UPLOAD_TOKEN, "application/octet-stream")).statusCode).toBe(415);
    expect((await upload(5, fakeApk())).statusCode).toBe(200);
    expect((await upload(5, fakeApk())).statusCode).toBe(409);
    expect((await upload(4, fakeApk())).statusCode).toBe(409);
    expect((await upload(6, fakeApk())).statusCode).toBe(200);
    expect(published).toEqual([5, 6]);
  });

  it("serves nothing before the first upload", async () => {
    const token = await signedInToken();
    const r = await app.inject({ method: "GET", url: "/api/v1/app/apk", headers: { authorization: `Bearer ${token}` } });
    expect(r.statusCode).toBe(404);
  });
});
