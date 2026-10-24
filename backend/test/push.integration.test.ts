import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import type { MediaGateway } from "../src/calls/media.js";
import { CallService } from "../src/calls/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { PushService, UnregisteredTokenError, type PushPayload, type PushTransport } from "../src/push/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

class RecordingTransport implements PushTransport {
  sent: { token: string; payload: PushPayload; high: boolean; ttl: number }[] = [];
  dead = new Set<string>();
  async send(token: string, payload: PushPayload, high: boolean, ttl: number) {
    if (this.dead.has(token)) throw new UnregisteredTokenError("gone");
    this.sent.push({ token, payload, high, ttl });
  }
}

const media: MediaGateway = {
  publicUrl: "wss://rtc.example.test",
  createRoom: async () => {},
  deleteRoom: async () => {},
  joinToken: async () => "jwt",
  verifyWebhook: async () => ({ event: "none" }),
};

const tokenA = "fcm-token-for-device-a-0123456789";
const tokenB = "fcm-token-for-device-b-0123456789";

describe.skipIf(!TEST_DB_URL)("push notifications (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let transport: RecordingTransport;
  let calls: CallService;
  let app: FastifyInstance;
  let ip = 0;
  let clockMs: number | null = null;
  const now = () => (clockMs === null ? new Date() : new Date(clockMs));

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }), now);
  });
  afterAll(async () => {
    await app?.close();
    await database.close();
  });
  beforeEach(async () => {
    clockMs = null;
    await resetDatabase(database.prisma);
    transport = new RecordingTransport();
    const push = new PushService(database.prisma, transport);
    calls = new CallService(database.prisma, media, 45, now, push);
    await app?.close();
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections: new ConnectionService(database.prisma, now),
      calls,
      media,
      push,
      logger: false,
    });
    await app.ready();
  });

  async function login(username: string) {
    const { temporaryPassword } = await auth.createUser(username, { type: "system", id: null });
    const remoteAddress = `192.0.2.${++ip % 250}`;
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
  const req = (token: string, method: "GET" | "POST" | "PUT" | "DELETE", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(payload ? { payload } : {}) });

  async function pair() {
    const alice = await login("alice");
    const bob = await login("bob");
    const qr = (await req(alice, "POST", "/connection-qr", {})).json();
    const r = (await req(bob, "POST", "/connection-requests", { token: qr.token })).json();
    const c = (await req(alice, "POST", `/connection-requests/${r.id}/accept`, {})).json();
    return { alice, bob, connectionId: c.id as string };
  }

  it("registers a device token and wakes the callee with an opaque high-priority push", async () => {
    const { alice, bob, connectionId } = await pair();
    expect((await req(bob, "PUT", "/devices/push-token", { fcmToken: tokenB })).statusCode).toBe(204);

    const s = (await req(alice, "POST", "/calls", { connectionId, kind: "video" })).json();
    await new Promise((r) => setTimeout(r, 50)); // push is fire-and-forget
    expect(transport.sent).toEqual([{ token: tokenB, payload: { t: "call", id: s.callId }, high: true, ttl: 45 }]);
    // Nothing identifying in the payload.
    expect(JSON.stringify(transport.sent[0]!.payload)).not.toContain("alice");
  });

  it("sends call_end when the caller cancels or nobody answers", async () => {
    const { alice, bob, connectionId } = await pair();
    await req(bob, "PUT", "/devices/push-token", { fcmToken: tokenB });
    const s1 = (await req(alice, "POST", "/calls", { connectionId, kind: "voice" })).json();
    await req(alice, "POST", `/calls/${s1.callId}/cancel`, {});
    const s2 = (await req(alice, "POST", "/calls", { connectionId, kind: "voice" })).json();
    clockMs = Date.now() + 46_000;
    await calls.sweep();
    await new Promise((r) => setTimeout(r, 50));
    expect(transport.sent.map((m) => m.payload)).toEqual([
      { t: "call", id: s1.callId },
      { t: "call_end", id: s1.callId },
      { t: "call", id: s2.callId },
      { t: "call_end", id: s2.callId },
    ]);
  });

  it("clears tokens that FCM reports as unregistered", async () => {
    const { alice, bob, connectionId } = await pair();
    await req(bob, "PUT", "/devices/push-token", { fcmToken: tokenB });
    transport.dead.add(tokenB);
    await req(alice, "POST", "/calls", { connectionId, kind: "video" });
    await new Promise((r) => setTimeout(r, 50));
    expect(await database.prisma.session.count({ where: { fcmToken: { not: null } } })).toBe(0);
  });

  it("moves a token to the newest session and drops it on logout", async () => {
    const alice = await login("alice");
    await req(alice, "PUT", "/devices/push-token", { fcmToken: tokenA });
    const bob = await login("bob");
    // Same physical device token registered by another session: it moves.
    await req(bob, "PUT", "/devices/push-token", { fcmToken: tokenA });
    const holders = await database.prisma.session.findMany({ where: { fcmToken: tokenA } });
    expect(holders).toHaveLength(1);

    await req(bob, "POST", "/auth/logout", {});
    expect(await database.prisma.session.count({ where: { fcmToken: { not: null } } })).toBe(0);
  });

  it("validates the token format", async () => {
    const alice = await login("alice");
    expect((await req(alice, "PUT", "/devices/push-token", { fcmToken: "short" })).statusCode).toBe(400);
    expect((await req(alice, "PUT", "/devices/push-token", { fcmToken: "x".repeat(30) + "<script>" })).statusCode).toBe(400);
  });
});
