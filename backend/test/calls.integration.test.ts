import { createHash } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { AccessToken } from "livekit-server-sdk";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { LiveKitGateway, type MediaGateway, type MediaWebhookEvent } from "../src/calls/media.js";
import { CallService } from "../src/calls/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const KEY = "devkey";
const SECRET = "test-only-livekit-secret-0123456789abcdef";

/** Real token signing and webhook verification; room operations recorded instead of sent. */
class RecordingGateway implements MediaGateway {
  private readonly real = new LiveKitGateway("wss://rtc.example.test", "http://127.0.0.1:1", KEY, SECRET);
  readonly publicUrl = this.real.publicUrl;
  created: string[] = [];
  deleted: string[] = [];
  failCreate = false;
  async createRoom(name: string) {
    if (this.failCreate) throw new Error("livekit down");
    this.created.push(name);
  }
  async deleteRoom(name: string) {
    this.deleted.push(name);
  }
  joinToken(identity: string, displayName: string, room: string) {
    return this.real.joinToken(identity, displayName, room);
  }
  verifyWebhook(body: string, auth: string | undefined): Promise<MediaWebhookEvent> {
    return this.real.verifyWebhook(body, auth);
  }
}

function jwtPayload(token: string) {
  return JSON.parse(Buffer.from(token.split(".")[1]!, "base64url").toString());
}

/** Signs a webhook body the way LiveKit does: a JWT carrying the body's SHA-256. */
async function signWebhook(body: string, secret = SECRET) {
  const at = new AccessToken(KEY, secret);
  at.sha256 = createHash("sha256").update(body).digest("base64");
  return at.toJwt();
}

describe.skipIf(!TEST_DB_URL)("calls (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let connections: ConnectionService;
  let calls: CallService;
  let media: RecordingGateway;
  let app: FastifyInstance;
  let clockMs: number | null = null;
  let ip = 0;
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
    await database.prisma.$executeRawUnsafe('TRUNCATE "call_diagnostics", "calls" CASCADE');
    media = new RecordingGateway();
    connections = new ConnectionService(database.prisma, now);
    calls = new CallService(database.prisma, media, 45, now);
    await app?.close();
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections,
      calls,
      media,
      logger: false,
    });
    await app.ready();
  });

  async function userToken(username: string): Promise<{ token: string; id: string }> {
    const { temporaryPassword, user } = await auth.createUser(username, { type: "system", id: null });
    const remoteAddress = `192.0.2.${++ip % 250}`;
    const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username, password: temporaryPassword }, remoteAddress })).json();
    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${first.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: "a long passphrase 123" },
      remoteAddress,
    });
    return { token: changed.json().accessToken, id: user.id };
  }

  const call = (token: string, method: "GET" | "POST", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(method === "POST" ? { payload: payload ?? {} } : {}) });

  async function connectedPair() {
    const alice = await userToken("alice");
    const bob = await userToken("bob");
    const qr = (await call(alice.token, "POST", "/connection-qr")).json();
    const req = (await call(bob.token, "POST", "/connection-requests", { token: qr.token })).json();
    const conn = (await call(alice.token, "POST", `/connection-requests/${req.id}/accept`)).json();
    return { alice, bob, connectionId: conn.id as string };
  }

  it("rings, is accepted, and gives each side a room-scoped token", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const start = await call(alice.token, "POST", "/calls", { connectionId, kind: "video" });
    expect(start.statusCode).toBe(201);
    const s = start.json();
    expect(s.livekitUrl).toBe("wss://rtc.example.test");
    expect(media.created).toHaveLength(1);
    const room = media.created[0]!;
    expect(room).toMatch(/^call_[0-9a-f-]{36}$/);

    const claims = jwtPayload(s.livekitToken);
    expect(claims.sub).toBe(alice.id);
    expect(claims.video).toMatchObject({ roomJoin: true, room, canPublish: true, canSubscribe: true, canPublishData: true });
    expect(claims.video.canPublishSources.sort()).toEqual(["camera", "microphone"]);
    expect(claims.video.roomCreate).toBeFalsy();
    expect(claims.video.roomAdmin).toBeFalsy();
    expect(claims.exp - claims.nbf).toBeLessThanOrEqual(600);

    const incoming = (await call(bob.token, "GET", "/calls/current")).json().call;
    expect(incoming).toMatchObject({ status: "ringing", role: "callee", kind: "video", peer: { username: "alice" } });

    const accepted = await call(bob.token, "POST", `/calls/${s.callId}/accept`);
    expect(accepted.statusCode).toBe(200);
    expect(jwtPayload(accepted.json().livekitToken).sub).toBe(bob.id);
    expect((await call(alice.token, "GET", `/calls/${s.callId}`)).json().status).toBe("accepted");
  });

  it("refuses to call someone you are not connected with, and when calling is not configured", async () => {
    const { alice, connectionId } = await connectedPair();
    const stranger = "00000000-0000-4000-8000-000000000000";
    expect((await call(alice.token, "POST", "/calls", { connectionId: stranger, kind: "video" })).statusCode).toBe(404);

    const noMedia = new CallService(database.prisma, null, 45, now);
    await expect(noMedia.start(alice.id, connectionId, "video")).rejects.toMatchObject({ statusCode: 503, code: "MEDIA_UNAVAILABLE" });
    media.failCreate = true;
    expect((await call(alice.token, "POST", "/calls", { connectionId, kind: "voice" })).json().error.code).toBe("MEDIA_UNAVAILABLE");
  });

  it("allows only one live call per pair, even when both call at once", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const [a, b] = await Promise.all([
      call(alice.token, "POST", "/calls", { connectionId, kind: "video" }),
      call(bob.token, "POST", "/calls", { connectionId, kind: "video" }),
    ]);
    expect([a.statusCode, b.statusCode].sort()).toEqual([201, 409]);
    expect((a.statusCode === 409 ? a : b).json().error.code).toBe("CALL_IN_PROGRESS");
    expect(await database.prisma.call.count()).toBe(1);
    // The losing side's room was cleaned up.
    expect(media.deleted.length).toBe(media.created.length - 1);
  });

  it("with several contacts (D-21), a person in a call is busy for everyone else", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const carol = await userToken("carol");
    const pair = async (owner: string, scanner: string) => {
      const qr = (await call(owner, "POST", "/connection-qr")).json();
      const req = (await call(scanner, "POST", "/connection-requests", { token: qr.token })).json();
      return (await call(owner, "POST", `/connection-requests/${req.id}/accept`)).json().id as string;
    };
    const bobCarol = await pair(bob.token, carol.token);
    const aliceCarol = await pair(alice.token, carol.token);

    expect((await call(alice.token, "POST", "/calls", { connectionId, kind: "voice" })).statusCode).toBe(201);
    const busy = await call(carol.token, "POST", "/calls", { connectionId: bobCarol, kind: "voice" });
    expect([busy.statusCode, busy.json().error.code]).toEqual([409, "PEER_BUSY"]);
    const self = await call(alice.token, "POST", "/calls", { connectionId: aliceCarol, kind: "voice" });
    expect([self.statusCode, self.json().error.code]).toEqual([409, "CALL_IN_PROGRESS"]);
    expect(await database.prisma.call.count()).toBe(1);
  });

  it("only the callee answers or declines; only the caller cancels", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const s = (await call(alice.token, "POST", "/calls", { connectionId, kind: "voice" })).json();
    expect((await call(alice.token, "POST", `/calls/${s.callId}/accept`)).statusCode).toBe(404);
    expect((await call(bob.token, "POST", `/calls/${s.callId}/cancel`)).statusCode).toBe(404);
    expect((await call(bob.token, "POST", `/calls/${s.callId}/decline`)).statusCode).toBe(204);
    expect((await call(bob.token, "POST", `/calls/${s.callId}/accept`)).json().error.code).toBe("CALL_NOT_ACTIVE");
    expect(media.deleted).toContain(media.created[0]);
  });

  it("marks unanswered calls as missed after the ring timeout", async () => {
    const { alice, connectionId } = await connectedPair();
    const s = (await call(alice.token, "POST", "/calls", { connectionId, kind: "video" })).json();
    clockMs = Date.now() + 46_000;
    expect(await calls.sweep()).toBe(1);
    expect((await call(alice.token, "GET", `/calls/${s.callId}`)).json()).toMatchObject({ status: "missed", endReason: "timeout" });
    // A new call can start afterwards.
    clockMs = null;
    expect((await call(alice.token, "POST", "/calls", { connectionId, kind: "video" })).statusCode).toBe(201);
  });

  it("fails accepted calls that nobody joins within 30 seconds", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const s = (await call(alice.token, "POST", "/calls", { connectionId, kind: "video" })).json();
    await call(bob.token, "POST", `/calls/${s.callId}/accept`);
    clockMs = Date.now() + 31_000;
    await calls.sweep();
    expect((await call(bob.token, "GET", `/calls/${s.callId}`)).json().status).toBe("failed");
  });

  it("verifies LiveKit webhook signatures and tracks join and room end", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const s = (await call(alice.token, "POST", "/calls", { connectionId, kind: "video" })).json();
    await call(bob.token, "POST", `/calls/${s.callId}/accept`);
    const room = media.created[0]!;
    const post = async (body: object, secret?: string) => {
      const text = JSON.stringify(body);
      return app.inject({
        method: "POST",
        url: "/internal/livekit/webhook",
        headers: { "content-type": "application/webhook+json", authorization: await signWebhook(text, secret) },
        payload: text,
      });
    };

    const forged = await post({ event: "room_finished", room: { name: room } }, "a-different-secret-0123456789abcdefgh");
    expect(forged.statusCode).toBe(401);
    expect((await call(alice.token, "GET", `/calls/${s.callId}`)).json().status).toBe("accepted");

    const tampered = await app.inject({
      method: "POST",
      url: "/internal/livekit/webhook",
      headers: { "content-type": "application/webhook+json", authorization: await signWebhook(JSON.stringify({ event: "x" })) },
      payload: JSON.stringify({ event: "room_finished", room: { name: room } }),
    });
    expect(tampered.statusCode).toBe(401);

    expect((await post({ event: "participant_joined", room: { name: room, numParticipants: 2 }, participant: { identity: bob.id } })).statusCode).toBe(200);
    expect((await call(alice.token, "GET", `/calls/${s.callId}`)).json().status).toBe("active");

    expect((await post({ event: "room_finished", room: { name: room } })).statusCode).toBe(200);
    expect((await call(alice.token, "GET", `/calls/${s.callId}`)).json()).toMatchObject({ status: "ended", endReason: "room_finished" });
  });

  it("hangs up idempotently and issues fresh tokens only for live calls", async () => {
    const { alice, bob, connectionId } = await connectedPair();
    const s = (await call(alice.token, "POST", "/calls", { connectionId, kind: "video" })).json();
    expect((await call(bob.token, "POST", `/calls/${s.callId}/token`)).json().error.code).toBe("CALL_NOT_ACTIVE");
    await call(bob.token, "POST", `/calls/${s.callId}/accept`);
    expect((await call(bob.token, "POST", `/calls/${s.callId}/token`)).statusCode).toBe(200);
    expect((await call(alice.token, "POST", `/calls/${s.callId}/end`, { reason: "hangup" })).statusCode).toBe(204);
    expect((await call(bob.token, "POST", `/calls/${s.callId}/end`, { reason: "hangup" })).statusCode).toBe(204);
    expect((await call(alice.token, "GET", `/calls/${s.callId}`)).json()).toMatchObject({ status: "ended", endReason: "hangup" });
    expect((await call(alice.token, "POST", `/calls/${s.callId}/token`)).json().error.code).toBe("CALL_NOT_ACTIVE");
    expect((await call(alice.token, "GET", "/calls/current")).json().call).toBeNull();
  });

  it("stores a bounded diagnostics summary per participant", async () => {
    const { alice, connectionId } = await connectedPair();
    const s = (await call(alice.token, "POST", "/calls", { connectionId, kind: "video" })).json();
    const ok = await call(alice.token, "POST", `/calls/${s.callId}/diagnostics`, { summary: { rttMsAvg: 82, path: "udp", fps: 29.5 } });
    expect(ok.statusCode).toBe(204);
    const nested = await call(alice.token, "POST", `/calls/${s.callId}/diagnostics`, { summary: { raw: { lots: "of data" } } });
    expect(nested.statusCode).toBe(400);
    expect(await database.prisma.callDiagnostic.count()).toBe(1);
  });
});
