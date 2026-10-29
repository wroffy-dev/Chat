import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { captureLogs, resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const NEW_PASSWORD = "a much better passphrase";

describe.skipIf(!TEST_DB_URL)("app-user authentication (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let app: FastifyInstance;
  let logs: string[];
  let clock: Date | null = null;
  const system = { type: "system" as const, id: null };

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    const hasher = new PasswordHasher({ memoryCost: 1024, timeCost: 1 });
    auth = new AuthService(database.prisma, hasher, () => clock ?? new Date());
    const captured = captureLogs(config);
    logs = captured.lines;
    app = buildApp({ config, database, auth, logger: captured.logger });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(async () => {
    clock = null;
    await resetDatabase(database.prisma);
  });

  // Route rate limits are per IP and the app instance is shared across tests, so each helper call
  // uses a fresh documentation-range address unless a test pins one deliberately.
  let ipCounter = 0;
  const nextIp = () => `192.0.2.${(ipCounter++ % 250) + 1}`;
  const login = (username: string, password: string, ip = nextIp()) =>
    app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username, password }, remoteAddress: ip });
  const me = (token: string) => app.inject({ method: "GET", url: "/api/v1/me", headers: { authorization: `Bearer ${token}` } });

  async function activeUser(username = "alice") {
    const { temporaryPassword } = await auth.createUser(username, system);
    const first = (await login(username, temporaryPassword)).json();
    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${first.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: NEW_PASSWORD },
      remoteAddress: nextIp(),
    });
    expect(changed.statusCode).toBe(200);
    return changed.json() as { accessToken: string; refreshToken: string; user: { id: string } };
  }

  it("allows at most ten users (D-21), with unique usernames", async () => {
    await auth.createUser("alice", system);
    await auth.createUser("Bob", system);
    await expect(auth.createUser("ALICE", system)).rejects.toMatchObject({ statusCode: 409 });
    for (let i = 3; i <= 10; i++) await auth.createUser(`user${i}`, system);
    await expect(auth.createUser("eleven", system)).rejects.toMatchObject({ statusCode: 409 });
    const users = await database.prisma.user.findMany({ orderBy: { slot: "asc" } });
    expect(users.map((u) => u.slot)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
    expect(users.slice(0, 2).map((u) => u.username)).toEqual(["alice", "bob"]);
    // The database agrees with MAX_USERS.
    await expect(database.prisma.$executeRaw`UPDATE users SET slot = 11 WHERE username = 'alice'`).rejects.toThrow();
  });

  it("returns the same error for unknown users and wrong passwords", async () => {
    await auth.createUser("alice", system);
    const unknown = await login("nobody", "whatever-password");
    const wrong = await login("alice", "whatever-password");
    expect(unknown.statusCode).toBe(401);
    expect(wrong.statusCode).toBe(401);
    expect(unknown.json().error.code).toBe("INVALID_CREDENTIALS");
    expect(wrong.json().error.message).toBe(unknown.json().error.message);
  });

  it("restricts a first-login session until the temporary password is changed", async () => {
    const { temporaryPassword } = await auth.createUser("alice", system);
    const res = await login("  Alice ", temporaryPassword);
    expect(res.statusCode).toBe(200);
    const body = res.json();
    expect(body.user).toMatchObject({ username: "alice", mustChangePassword: true });
    expect(body.accessToken).toMatch(/^vca_/);
    expect(body.refreshToken).toMatch(/^vcr_/);

    const blocked = await me(body.accessToken);
    expect(blocked.statusCode).toBe(403);
    expect(blocked.json().error.code).toBe("PASSWORD_CHANGE_REQUIRED");

    const weak = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${body.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: "short" },
    });
    expect(weak.statusCode).toBe(422);
    expect(weak.json().error.code).toBe("WEAK_PASSWORD");

    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${body.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: NEW_PASSWORD },
    });
    expect(changed.statusCode).toBe(200);
    const fresh = changed.json();
    expect(fresh.user.mustChangePassword).toBe(false);
    expect((await me(body.accessToken)).statusCode).toBe(401); // old token rotated away
    const ok = await me(fresh.accessToken);
    expect(ok.statusCode).toBe(200);
    expect(ok.json()).toEqual({ id: fresh.user.id, username: "alice", mustChangePassword: false, connection: null });
  });

  it("rejects missing, malformed and unknown tokens", async () => {
    expect((await app.inject({ method: "GET", url: "/api/v1/me" })).json().error.code).toBe("UNAUTHENTICATED");
    expect((await me("not a token!")).statusCode).toBe(401);
    expect((await me("vca_" + "A".repeat(43))).json().error.code).toBe("SESSION_REVOKED");
  });

  it("rotates refresh tokens and revokes the session when an old one is reused", async () => {
    const s = await activeUser();
    const r1 = await app.inject({ method: "POST", url: "/api/v1/auth/refresh", payload: { refreshToken: s.refreshToken } });
    expect(r1.statusCode).toBe(200);
    const t1 = r1.json();
    expect(t1.refreshToken).not.toBe(s.refreshToken);
    expect((await me(t1.accessToken)).statusCode).toBe(200);

    // The pre-rotation token shows up again: treat it as stolen and end the session.
    const replay = await app.inject({ method: "POST", url: "/api/v1/auth/refresh", payload: { refreshToken: s.refreshToken } });
    expect(replay.statusCode).toBe(401);
    expect((await me(t1.accessToken)).json().error.code).toBe("SESSION_REVOKED");
    const r2 = await app.inject({ method: "POST", url: "/api/v1/auth/refresh", payload: { refreshToken: t1.refreshToken } });
    expect(r2.statusCode).toBe(401);
    const events = await database.prisma.auditEvent.findMany({ where: { action: "auth.refresh_reuse_detected" } });
    expect(events).toHaveLength(1);
  });

  it("expires access tokens after 15 minutes but allows refresh", async () => {
    const s = await activeUser();
    clock = new Date(Date.now() + 16 * 60_000);
    const res = await me(s.accessToken);
    expect(res.statusCode).toBe(401);
    expect(res.json().error.code).toBe("TOKEN_EXPIRED");
    const r = await app.inject({ method: "POST", url: "/api/v1/auth/refresh", payload: { refreshToken: s.refreshToken } });
    expect(r.statusCode).toBe(200);
    expect((await me(r.json().accessToken)).statusCode).toBe(200);
  });

  it("ends the session after 30 idle days", async () => {
    const s = await activeUser();
    clock = new Date(Date.now() + 31 * 24 * 3600_000);
    const r = await app.inject({ method: "POST", url: "/api/v1/auth/refresh", payload: { refreshToken: s.refreshToken } });
    expect(r.json().error.code).toBe("SESSION_REVOKED");
  });

  it("keeps one active session per user: a new login ends the previous one", async () => {
    const s = await activeUser();
    const second = (await login("alice", NEW_PASSWORD)).json();
    expect((await me(s.accessToken)).json().error.code).toBe("SESSION_REVOKED");
    expect((await me(second.accessToken)).statusCode).toBe(200);
  });

  it("logs out", async () => {
    const s = await activeUser();
    const out = await app.inject({ method: "POST", url: "/api/v1/auth/logout", headers: { authorization: `Bearer ${s.accessToken}` }, payload: {} });
    expect(out.statusCode).toBe(204);
    expect((await me(s.accessToken)).statusCode).toBe(401);
  });

  it("throttles an account after five wrong passwords, with Retry-After", async () => {
    await activeUser();
    for (let i = 0; i < 5; i++) expect((await login("alice", `wrong-${i}`, `198.51.100.${10 + i}`)).statusCode).toBe(401);
    const blocked = await login("alice", NEW_PASSWORD, "198.51.100.99");
    expect(blocked.statusCode).toBe(429);
    expect(blocked.json().error.code).toBe("RATE_LIMITED");
    expect(Number(blocked.headers["retry-after"])).toBeGreaterThan(0);
    clock = new Date(Date.now() + 2 * 60_000);
    expect((await login("alice", NEW_PASSWORD, "198.51.100.98")).statusCode).toBe(200);
  });

  it("rate-limits login attempts per IP", async () => {
    const codes: number[] = [];
    for (let i = 0; i < 11; i++) codes.push((await login(`user${i}`, "x", "203.0.113.5")).statusCode);
    expect(codes.slice(0, 10).every((c) => c === 401)).toBe(true);
    expect(codes[10]).toBe(429);
  });

  it("admin password reset revokes sessions and forces a new password", async () => {
    const s = await activeUser();
    const temp = await auth.resetPassword(s.user.id, system);
    expect((await me(s.accessToken)).json().error.code).toBe("SESSION_REVOKED");
    expect((await login("alice", NEW_PASSWORD)).statusCode).toBe(401);
    const again = (await login("alice", temp)).json();
    expect(again.user.mustChangePassword).toBe(true);
  });

  it("disabling a user ends their sessions and blocks login", async () => {
    const s = await activeUser();
    await auth.setUserStatus(s.user.id, "disabled", system);
    expect((await me(s.accessToken)).statusCode).toBe(401);
    const res = await login("alice", NEW_PASSWORD);
    expect(res.statusCode).toBe(403);
    expect(res.json().error.code).toBe("ACCOUNT_DISABLED");
    await auth.setUserStatus(s.user.id, "active", system);
    expect((await login("alice", NEW_PASSWORD)).statusCode).toBe(200);
  });

  it("stores only hashes and never logs passwords or tokens", async () => {
    const s = await activeUser();
    const session = await database.prisma.session.findFirstOrThrow({ where: { revokedAt: null } });
    expect(Buffer.from(session.accessTokenHash).toString()).not.toContain(s.accessToken);
    const user = await database.prisma.user.findFirstOrThrow();
    expect(user.passwordHash).toMatch(/^\$argon2id\$/);
    const output = logs.join("");
    for (const secret of [NEW_PASSWORD, s.accessToken, s.refreshToken]) expect(output).not.toContain(secret);
  });

  it("rejects unknown request fields", async () => {
    const res = await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username: "a", password: "b", isAdmin: true } });
    expect(res.statusCode).toBe(400);
  });
});
