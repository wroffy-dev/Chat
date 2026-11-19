import { mkdtempSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { SnapService, streakDay } from "../src/snaps/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(600, 7)]);
const MP4 = Buffer.concat([Buffer.from([0, 0, 0, 24]), Buffer.from("ftypmp42"), Buffer.alloc(600, 3)]);

describe.skipIf(!TEST_DB_URL)("snaps and streaks (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let snaps: SnapService;
  let app: FastifyInstance;
  let dir: string;
  let clockMs: number | null = null;
  let ip = 0;
  const now = () => (clockMs === null ? new Date() : new Date(clockMs));

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    clockMs = null;
    await resetDatabase(database.prisma);
    await database.prisma.$executeRawUnsafe('TRUNCATE "snaps", "snap_streaks" CASCADE');
    dir = mkdtempSync(join(tmpdir(), "snaps-"));
    snaps = new SnapService(database.prisma, dir, now);
    const connections = new ConnectionService(database.prisma);
    connections.snaps = snaps;
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections,
      snaps,
      logger: false,
    });
    await app.ready();
  });
  afterEach(async () => {
    await app.close();
    rmSync(dir, { recursive: true, force: true });
  });

  async function login(username: string) {
    const { temporaryPassword } = await auth.createUser(username, { type: "system", id: null });
    const remoteAddress = `203.0.113.${++ip % 250}`;
    const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username, password: temporaryPassword }, remoteAddress })).json();
    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${first.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: "a long passphrase 123" },
      remoteAddress,
    });
    return changed.json().accessToken as string;
  }
  const req = (token: string, method: "GET" | "POST", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(payload ? { payload } : {}) });
  const upload = (token: string, connectionId: string, kind: "photo" | "video", body: Buffer, type = kind === "photo" ? "image/jpeg" : "video/mp4") =>
    app.inject({ method: "POST", url: `/api/v1/connections/${connectionId}/snaps?kind=${kind}`, headers: { authorization: `Bearer ${token}`, "content-type": type }, payload: body });

  async function pair() {
    const alice = await login("alice");
    const bob = await login("bob");
    const qr = (await req(alice, "POST", "/connection-qr", {})).json();
    const r = (await req(bob, "POST", "/connection-requests", { token: qr.token })).json();
    const accepted = (await req(alice, "POST", `/connection-requests/${r.id}/accept`, {})).json();
    return { alice, bob, connectionId: accepted.id as string };
  }

  it("sends a photo, lists it for the recipient only, opens it once and deletes it", async () => {
    const { alice, bob, connectionId } = await pair();
    const sent = await upload(alice, connectionId, "photo", JPEG);
    expect(sent.statusCode).toBe(201);
    expect(readdirSync(dir)).toHaveLength(1);

    expect((await req(alice, "GET", "/snaps")).json().items).toEqual([]);
    const inbox = (await req(bob, "GET", "/snaps")).json().items;
    expect(inbox).toEqual([expect.objectContaining({ id: sent.json().id, connectionId, kind: "photo", from: expect.objectContaining({ username: "alice" }) })]);
    expect((await req(bob, "GET", "/connections")).json().items[0]).toMatchObject({ pendingSnaps: 1 });

    expect((await req(alice, "POST", `/snaps/${sent.json().id}/open`, {})).statusCode).toBe(404);
    const opened = await req(bob, "POST", `/snaps/${sent.json().id}/open`, {});
    expect(opened.statusCode).toBe(200);
    expect(opened.headers["content-type"]).toContain("image/jpeg");
    expect(opened.rawPayload.equals(JPEG)).toBe(true);
    // Gone from the server: a second open is refused and the file is deleted.
    expect((await req(bob, "POST", `/snaps/${sent.json().id}/open`, {})).statusCode).toBe(404);
    expect(readdirSync(dir)).toHaveLength(0);
    expect((await req(bob, "GET", "/snaps")).json().items).toEqual([]);
  });

  it("refuses files that are not a camera photo or video, the wrong type, too large, or a stranger's connection", async () => {
    const { alice, connectionId } = await pair();
    expect((await upload(alice, connectionId, "photo", Buffer.alloc(800, 1))).statusCode).toBe(400);
    expect((await upload(alice, connectionId, "video", MP4, "image/jpeg")).statusCode).toBe(415);
    expect((await upload(alice, connectionId, "video", MP4)).statusCode).toBe(201);
    expect((await upload(alice, connectionId, "photo", Buffer.concat([JPEG, Buffer.alloc(7 * 1024 * 1024)]))).statusCode).toBe(413);
    const carol = await login("carol");
    expect((await upload(carol, connectionId, "photo", JPEG)).statusCode).toBe(404);
    // Refused uploads leave no file behind.
    expect(readdirSync(dir)).toHaveLength(1);
  });

  it("deletes snaps nobody opened within 24 hours", async () => {
    const { alice, bob, connectionId } = await pair();
    await upload(alice, connectionId, "photo", JPEG);
    clockMs = Date.now() + 25 * 3600_000;
    expect(await snaps.sweep()).toBe(1);
    expect(readdirSync(dir)).toHaveLength(0);
    expect((await req(bob, "GET", "/snaps")).json().items).toEqual([]);
  });

  it("streak: grows on days both sent, survives one quiet day showing, resets after a missed day", async () => {
    const { alice, bob, connectionId } = await pair();
    const day0 = Date.parse("2026-11-10T06:00:00Z");
    const at = (days: number) => { clockMs = day0 + days * 86_400_000; };
    const streak = async () => (await req(alice, "GET", "/connections")).json().items[0].streak;

    at(0);
    expect((await upload(alice, connectionId, "photo", JPEG)).json().streak).toBe(0); // only one side so far
    expect((await upload(bob, connectionId, "photo", JPEG)).json().streak).toBe(1);
    at(1);
    await upload(bob, connectionId, "photo", JPEG);
    expect(await streak()).toBe(1); // still counts while today is not over
    await upload(alice, connectionId, "photo", JPEG);
    expect(await streak()).toBe(2);
    at(3); // day 2 was missed
    expect(await streak()).toBe(0);
    await upload(alice, connectionId, "photo", JPEG);
    await upload(bob, connectionId, "photo", JPEG);
    expect(await streak()).toBe(1);
    expect(streakDay(new Date("2026-11-10T19:00:00Z"))).toBe("2026-11-11"); // India time: 00:30 next day
  });
});
