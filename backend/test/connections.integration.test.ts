import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const PW = "a long passphrase 123";

describe.skipIf(!TEST_DB_URL)("QR connection (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let connections: ConnectionService;
  let app: FastifyInstance;
  let clockMs: number | null = null;
  let ip = 0;

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    const now = () => (clockMs === null ? new Date() : new Date(clockMs));
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }), now);
    connections = new ConnectionService(database.prisma, now);
    app = buildApp({ config, database, auth, connections, logger: false });
    await app.ready();
  });

  afterAll(async () => {
    await app.close();
  });

  beforeEach(async () => {
    clockMs = null;
    await resetDatabase(database.prisma);
    // Fresh in-memory per-user limits for each test.
    connections = new ConnectionService(database.prisma, () => (clockMs === null ? new Date() : new Date(clockMs)));
    await app.close();
    app = buildApp({ config, database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} }, auth, connections, logger: false });
    await app.ready();
  });

  /** Creates a user who has already replaced the temporary password; returns an access token. */
  async function userToken(username: string): Promise<string> {
    const { temporaryPassword } = await auth.createUser(username, { type: "system", id: null });
    const remoteAddress = `192.0.2.${++ip % 250}`;
    const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username, password: temporaryPassword }, remoteAddress })).json();
    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${first.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: PW },
      remoteAddress,
    });
    return changed.json().accessToken;
  }

  const call = (token: string, method: "GET" | "POST", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(method === "POST" ? { payload: payload ?? {} } : {}) });

  const qr = async (token: string) => (await call(token, "POST", "/connection-qr")).json() as { token: string; qrPayload: string; expiresAt: string };

  it("pairs two users: generate → scan → accept → permanent connection", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");

    const code = await qr(alice);
    expect(code.qrPayload).toBe(`vaultchat://connect?v=1&t=${code.token}`);
    expect(code.qrPayload).not.toContain("alice");

    const scan = await call(bob, "POST", "/connection-requests", { token: code.token });
    expect(scan.statusCode).toBe(201);
    expect(scan.json()).toMatchObject({ direction: "outgoing", status: "pending", peer: { username: "alice" } });

    const incoming = (await call(alice, "GET", "/connection-requests?direction=incoming&status=pending")).json().items;
    expect(incoming).toHaveLength(1);
    expect(incoming[0].peer.username).toBe("bob");

    const accepted = await call(alice, "POST", `/connection-requests/${incoming[0].id}/accept`);
    expect(accepted.statusCode).toBe(200);
    expect(accepted.json().peer.username).toBe("bob");

    const me = (await call(bob, "GET", "/me")).json();
    expect(me.connection.peer.username).toBe("alice");
    expect((await call(alice, "GET", "/connections")).json().items).toHaveLength(1);

    // Once connected, no more codes.
    expect((await call(alice, "POST", "/connection-qr")).json().error.code).toBe("ALREADY_CONNECTED");
  });

  it("uses one generic error for unknown, expired, used, superseded and own tokens", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const bogus = "vcq_" + "A".repeat(43);
    const codes: string[] = [];

    codes.push((await call(bob, "POST", "/connection-requests", { token: bogus })).json().error.code);

    const own = await qr(alice);
    codes.push((await call(alice, "POST", "/connection-requests", { token: own.token })).json().error.code);

    const old = await qr(alice);
    await qr(alice); // supersedes `old`
    codes.push((await call(bob, "POST", "/connection-requests", { token: old.token })).json().error.code);

    const expiring = await qr(alice);
    clockMs = Date.now() + 61_000;
    codes.push((await call(bob, "POST", "/connection-requests", { token: expiring.token })).json().error.code);

    expect(codes).toEqual(["QR_INVALID_OR_EXPIRED", "QR_INVALID_OR_EXPIRED", "QR_INVALID_OR_EXPIRED", "QR_INVALID_OR_EXPIRED"]);
  });

  it("lets only one of two simultaneous scans of the same code succeed", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const code = await qr(alice);
    const results = await Promise.all([
      call(bob, "POST", "/connection-requests", { token: code.token }),
      call(bob, "POST", "/connection-requests", { token: code.token }),
    ]);
    expect(results.map((r) => r.statusCode).sort()).toEqual([201, 410]);
    expect(await database.prisma.connectionRequest.count()).toBe(1);
  });

  it("refreshing the QR after a scan does not break the pending request", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const code = await qr(alice);
    const req = (await call(bob, "POST", "/connection-requests", { token: code.token })).json();
    await qr(alice);
    expect((await call(alice, "POST", `/connection-requests/${req.id}/accept`)).statusCode).toBe(200);
  });

  it("only the QR owner can accept or reject; only the requester can cancel", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const req = (await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token })).json();
    expect((await call(bob, "POST", `/connection-requests/${req.id}/accept`)).statusCode).toBe(404);
    expect((await call(alice, "POST", `/connection-requests/${req.id}/cancel`)).statusCode).toBe(404);
    expect((await call(alice, "POST", `/connection-requests/${req.id}/reject`)).statusCode).toBe(204);
    expect((await call(alice, "POST", `/connection-requests/${req.id}/accept`)).json().error.code).toBe("REQUEST_NOT_PENDING");
    expect(await database.prisma.connection.count()).toBe(0);
  });

  it("expires unanswered requests after five minutes", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const req = (await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token })).json();
    clockMs = Date.now() + 5 * 60_000 + 1000;
    expect((await call(alice, "POST", `/connection-requests/${req.id}/accept`)).json().error.code).toBe("REQUEST_NOT_PENDING");
    const list = (await call(bob, "GET", "/connection-requests?direction=outgoing")).json().items;
    expect(list[0].status).toBe("expired");
    // A new code works after expiry (the "one pending" index was released).
    expect((await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token })).statusCode).toBe(201);
  });

  it("refuses a second pending request in the same direction", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token });
    const second = await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token });
    expect(second.json().error.code).toBe("REQUEST_ALREADY_PENDING");
  });

  it("cross-scans: both accept at once and exactly one connection exists", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const r1 = (await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token })).json();
    const r2 = (await call(alice, "POST", "/connection-requests", { token: (await qr(bob)).token })).json();
    const [a, b] = await Promise.all([
      call(alice, "POST", `/connection-requests/${r1.id}/accept`),
      call(bob, "POST", `/connection-requests/${r2.id}/accept`),
    ]);
    const statuses = [a.statusCode, b.statusCode].sort();
    // Either both succeed (each saw its own pending request) or the second finds its request already cancelled.
    expect([[200, 200], [200, 409]]).toContainEqual(statuses);
    expect(await database.prisma.connection.count()).toBe(1);
  });

  it("the database refuses to delete or alter a connection", async () => {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const req = (await call(bob, "POST", "/connection-requests", { token: (await qr(alice)).token })).json();
    await call(alice, "POST", `/connection-requests/${req.id}/accept`);
    await expect(database.prisma.connection.deleteMany({})).rejects.toThrow();
    const c = await database.prisma.connection.findFirstOrThrow();
    await expect(
      database.prisma.connection.update({ where: { id: c.id }, data: { userAId: c.userBId, userBId: c.userAId } }),
    ).rejects.toThrow();
    expect(await database.prisma.connection.count()).toBe(1);
  });

  it("rate-limits QR generation per user", async () => {
    const alice = await userToken("alice");
    const statuses: number[] = [];
    for (let i = 0; i < 7; i++) statuses.push((await call(alice, "POST", "/connection-qr")).statusCode);
    expect(statuses.slice(0, 6).every((s) => s === 201)).toBe(true);
    expect(statuses[6]).toBe(429);
  });

  it("stores only a hash of the QR token", async () => {
    const alice = await userToken("alice");
    const code = await qr(alice);
    const row = await database.prisma.qrToken.findFirstOrThrow();
    expect(Buffer.from(row.tokenHash).toString("utf8")).not.toContain(code.token);
  });
});
