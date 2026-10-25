import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance, LightMyRequestResponse } from "fastify";
import { buildAdminApp } from "../src/admin/app.js";
import { AdminService } from "../src/admin/service.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { generateTotpSecret, totpCode, totpStep } from "../src/auth/totp.js";
import { PrismaDatabase } from "../src/db/database.js";
import { RealtimeHub } from "../src/realtime/hub.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const ADMIN_PASSWORD = "a long admin passphrase 2026";

describe.skipIf(!TEST_DB_URL)("Admin Console (PostgreSQL)", () => {
  const backupStatusFile = join(mkdtempSync(join(tmpdir(), "vc-admin-")), "last-success");
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x", BACKUP_STATUS_FILE: backupStatusFile });
  let database: PrismaDatabase;
  let adminService: AdminService;
  let auth: AuthService;
  let app: FastifyInstance;
  let secret: Buffer;
  // Each login needs an unused TOTP step, so tests move a fake clock forward.
  let nowMs = Date.UTC(2026, 9, 24, 12, 0, 0);

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    const hasher = new PasswordHasher({ memoryCost: 1024, timeCost: 1 });
    const clock = () => new Date(nowMs);
    auth = new AuthService(database.prisma, hasher, clock);
    adminService = new AdminService(database.prisma, hasher, config.ADMIN_TOTP_ENC_KEY, clock);
    const media = {
      publicUrl: "wss://rtc.example.test",
      createRoom: async () => {},
      deleteRoom: async () => {},
      joinToken: async () => "jwt",
      verifyWebhook: async () => ({ event: "none" }),
      ping: async () => {},
    };
    app = buildAdminApp({ config, prisma: database.prisma, database, auth, admin: adminService, media, pushEnabled: true, realtime: new RealtimeHub(), logger: false });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
    await database.close();
  });

  beforeEach(async () => {
    await resetDatabase(database.prisma);
    nowMs += 120_000;
  });

  async function createAdmin() {
    secret = generateTotpSecret();
    await adminService.createAdmin("Root", ADMIN_PASSWORD, secret);
  }

  const code = () => totpCode(secret, totpStep(nowMs));

  async function loginCookie(): Promise<{ cookie: string; csrf: string }> {
    nowMs += 60_000;
    const res = await app.inject({
      method: "POST",
      url: "/admin/login",
      payload: { username: "root", password: ADMIN_PASSWORD, totp: code() },
    });
    expect(res.statusCode).toBe(303);
    const setCookie = String(res.headers["set-cookie"]);
    const cookie = setCookie.split(";")[0]!;
    const users = await app.inject({ method: "GET", url: "/admin/users", headers: { cookie } });
    const csrf = /name="csrf" value="([^"]+)"/.exec(users.body)![1]!;
    return { cookie, csrf };
  }

  const post = (url: string, cookie: string, payload: Record<string, string>) =>
    app.inject({ method: "POST", url, headers: { cookie }, payload });

  const temporaryPasswordIn = (res: LightMyRequestResponse) => /<code class="secret">([^<]+)<\/code>/.exec(res.body)?.[1];

  it("shows setup instructions, not a registration form, before an admin exists", async () => {
    const res = await app.inject({ method: "GET", url: "/admin/login" });
    expect(res.body).toContain("admin:create");
    expect(res.body).not.toContain("<form");
  });

  it("allows exactly one administrator", async () => {
    await createAdmin();
    await expect(adminService.createAdmin("other", ADMIN_PASSWORD, generateTotpSecret())).rejects.toThrow(/already exists/);
  });

  it("requires password and a fresh TOTP code, and sets a hardened cookie", async () => {
    await createAdmin();
    const wrongCode = await app.inject({ method: "POST", url: "/admin/login", payload: { username: "root", password: ADMIN_PASSWORD, totp: "000000" } });
    expect(wrongCode.statusCode).toBe(401);

    const usedCode = code();
    const ok = await app.inject({ method: "POST", url: "/admin/login", payload: { username: "root", password: ADMIN_PASSWORD, totp: usedCode } });
    expect(ok.statusCode).toBe(303);
    const setCookie = String(ok.headers["set-cookie"]);
    expect(setCookie).toMatch(/HttpOnly/);
    expect(setCookie).toMatch(/Secure/);
    expect(setCookie).toMatch(/SameSite=Strict/);
    expect(ok.headers["content-security-policy"]).toContain("default-src 'none'");

    const replay = await app.inject({ method: "POST", url: "/admin/login", payload: { username: "root", password: ADMIN_PASSWORD, totp: usedCode } });
    expect(replay.statusCode).toBe(401);
  });

  it("throttles the admin login after five failures", async () => {
    await createAdmin();
    for (let i = 0; i < 5; i++) {
      await app.inject({ method: "POST", url: "/admin/login", payload: { username: "root", password: "wrong password here", totp: "123456" } });
    }
    const res = await app.inject({ method: "POST", url: "/admin/login", payload: { username: "root", password: ADMIN_PASSWORD, totp: code() } });
    expect(res.statusCode).toBe(429);
  });

  it("redirects unauthenticated requests to login", async () => {
    await createAdmin();
    const res = await app.inject({ method: "GET", url: "/admin/users" });
    expect(res.statusCode).toBe(303);
    expect(res.headers.location).toBe("/admin/login");
  });

  it("rejects state-changing requests without the CSRF token", async () => {
    await createAdmin();
    const { cookie } = await loginCookie();
    const res = await post("/admin/users", cookie, { csrf: "forged", username: "alice" });
    expect(res.statusCode).toBe(403);
    expect(await database.prisma.user.count()).toBe(0);
  });

  it("creates the two users, shows each temporary password once, and stops at two", async () => {
    await createAdmin();
    const { cookie, csrf } = await loginCookie();
    const first = await post("/admin/users", cookie, { csrf, username: "alice" });
    expect(first.statusCode).toBe(200);
    const temp = temporaryPasswordIn(first);
    expect(temp).toHaveLength(18);
    await post("/admin/users", cookie, { csrf, username: "bob" });

    const list = await app.inject({ method: "GET", url: "/admin/users", headers: { cookie } });
    expect(list.body).toContain("Both user accounts exist");
    expect(list.body).not.toContain(temp!);
    expect(list.body).not.toContain("$argon2");

    const third = await post("/admin/users", cookie, { csrf, username: "carol" });
    expect(third.statusCode).toBe(409);
  });

  it("resets a password, revokes sessions, and records audit events", async () => {
    await createAdmin();
    const { cookie, csrf } = await loginCookie();
    const { user, temporaryPassword } = await auth.createUser("alice", { type: "system", id: null });
    await auth.login("alice", temporaryPassword, { ip: null });

    const reset = await post(`/admin/users/${user.id}/reset-password`, cookie, { csrf });
    expect(reset.statusCode).toBe(200);
    const newTemp = temporaryPasswordIn(reset);
    expect(newTemp).toBeTruthy();
    expect(newTemp).not.toBe(temporaryPassword);
    expect(await database.prisma.session.count({ where: { userId: user.id, revokedAt: null } })).toBe(0);

    const disable = await post(`/admin/users/${user.id}/disable`, cookie, { csrf });
    expect(disable.statusCode).toBe(303);
    expect((await database.prisma.user.findUniqueOrThrow({ where: { id: user.id } })).status).toBe("disabled");

    const audit = await app.inject({ method: "GET", url: "/admin/audit", headers: { cookie } });
    for (const action of ["admin.login_succeeded", "admin.user_password_reset", "admin.user_disabled"]) {
      expect(audit.body).toContain(action);
    }
    expect(audit.body).not.toContain(newTemp!);
  });

  it("escapes user-controlled values in HTML", async () => {
    await createAdmin();
    const { cookie } = await loginCookie();
    const { user } = await auth.createUser("alice", { type: "system", id: null });
    await database.prisma.session.create({
      data: {
        userId: user.id,
        accessTokenHash: new Uint8Array(32).fill(1),
        refreshTokenHash: new Uint8Array(32).fill(2),
        accessExpiresAt: new Date(nowMs + 60_000),
        idleExpiresAt: new Date(nowMs + 60_000),
        absoluteExpiresAt: new Date(nowMs + 60_000),
        deviceModel: "<script>alert(1)</script>",
      },
    });
    const res = await app.inject({ method: "GET", url: "/admin/users", headers: { cookie } });
    expect(res.body).not.toContain("<script>alert(1)</script>");
    expect(res.body).toContain("&lt;script&gt;");
  });

  it("logs out and invalidates the cookie", async () => {
    await createAdmin();
    const { cookie, csrf } = await loginCookie();
    const out = await post("/admin/logout", cookie, { csrf });
    expect(out.statusCode).toBe(303);
    const after = await app.inject({ method: "GET", url: "/admin/users", headers: { cookie } });
    expect(after.headers.location).toBe("/admin/login");
  });

  it("health shows LiveKit, push, live connections and the last backup", async () => {
    await createAdmin();
    const { cookie } = await loginCookie();
    let page = (await app.inject({ method: "GET", url: "/admin/health", headers: { cookie } })).body;
    expect(page).toContain("reachable");
    expect(page).toContain("configured");
    expect(page).toContain("no successful backup reported yet");
    writeFileSync(backupStatusFile, new Date(Date.now() - 2 * 3_600_000).toISOString());
    page = (await app.inject({ method: "GET", url: "/admin/health", headers: { cookie } })).body;
    expect(page).toMatch(/\(2\.0 h ago\)/);
    writeFileSync(backupStatusFile, new Date(Date.now() - 40 * 3_600_000).toISOString());
    page = (await app.inject({ method: "GET", url: "/admin/health", headers: { cookie } })).body;
    expect(page).toContain("OLDER THAN 36 HOURS");
  });

  it("lists recent calls with each phone's quality summary, escaped", async () => {
    await createAdmin();
    const db = database.prisma;
    const a = await db.user.create({ data: { slot: 1, username: "alice", passwordHash: "x", passwordChangedAt: new Date() } });
    const b = await db.user.create({ data: { slot: 2, username: "bob", passwordHash: "x", passwordChangedAt: new Date() } });
    const [ua, ub] = [a.id, b.id].sort();
    const conn = await db.connection.create({ data: { userAId: ua!, userBId: ub! } });
    const call = await db.call.create({
      data: { connectionId: conn.id, callerUserId: a.id, calleeUserId: b.id, kind: "video", livekitRoom: "r1", status: "ended",
        activeAt: new Date(Date.now() - 65_000), endedAt: new Date() },
    });
    await db.callDiagnostic.create({ data: { callId: call.id, userId: a.id, summary: { rttMsAvg: 84, lossPct: 0.4, path: "<script>x</script>" } } });
    const { cookie } = await loginCookie();
    const page = (await app.inject({ method: "GET", url: "/admin/calls", headers: { cookie } })).body;
    expect(page).toContain("alice → bob");
    expect(page).toContain("65 s");
    expect(page).toContain("rttMsAvg=84");
    expect(page).toContain("lossPct=0.4");
    expect(page).not.toContain("<script>x");
    expect(page).toContain("&lt;script&gt;");
  });
});
