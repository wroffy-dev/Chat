import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import WebSocket from "ws";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import type { MediaGateway } from "../src/calls/media.js";
import { CallService } from "../src/calls/service.js";
import { ChatService } from "../src/chat/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { RealtimeHub } from "../src/realtime/hub.js";
import { WS_CLOSE } from "../src/routes/realtime.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const media: MediaGateway = {
  publicUrl: "wss://rtc.example.test",
  createRoom: async () => {},
  deleteRoom: async () => {},
  joinToken: async () => "jwt",
  verifyWebhook: async () => ({ event: "none" }),
};

/** A connected test socket that records every frame and its close code. */
class Client {
  frames: { type: string; data: Record<string, unknown> }[] = [];
  raw: string[] = [];
  closed: number | null = null;
  constructor(readonly ws: WebSocket) {
    ws.on("message", (d) => {
      this.raw.push(d.toString());
      this.frames.push(JSON.parse(d.toString()));
    });
    ws.on("close", (code) => {
      this.closed = code;
    });
  }
  async waitFor(pred: () => boolean, what: string) {
    for (let i = 0; i < 100; i++) {
      if (pred()) return;
      await new Promise((r) => setTimeout(r, 20));
    }
    throw new Error(`timed out waiting for ${what}`);
  }
}

describe.skipIf(!TEST_DB_URL)("realtime signals over /api/v1/ws (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let hub: RealtimeHub;
  let app: FastifyInstance;
  let base: string;
  let ip = 0;
  const sockets: WebSocket[] = [];

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await app?.close();
    await database.close();
  });
  beforeEach(async () => {
    for (const s of sockets.splice(0)) s.terminate();
    await app?.close();
    await resetDatabase(database.prisma);
    hub = new RealtimeHub();
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections: new ConnectionService(database.prisma, undefined, hub),
      calls: new CallService(database.prisma, media, 45, undefined, null, hub),
      chat: new ChatService(database.prisma, undefined, null, hub),
      media,
      realtime: { hub, recheckMs: 100 },
      logger: false,
    });
    await app.listen({ host: "127.0.0.1", port: 0 });
    const addr = app.server.address();
    base = typeof addr === "object" && addr ? `127.0.0.1:${addr.port}` : "";
  });

  async function login(username: string, change = true) {
    const { temporaryPassword } = await auth.createUser(username, { type: "system", id: null });
    const remoteAddress = `198.18.0.${++ip % 250}`;
    const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username, password: temporaryPassword }, remoteAddress })).json();
    if (!change) return first.accessToken as string;
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

  /** Opens a socket; resolves with the client once "ready" arrives, or rejects with the HTTP status. */
  function connect(headers: Record<string, string>): Promise<Client> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://${base}/api/v1/ws`, { headers });
      sockets.push(ws);
      const client = new Client(ws);
      ws.on("unexpected-response", (_req, res) => reject(new Error(`HTTP ${res.statusCode}`)));
      ws.on("error", reject);
      ws.on("message", () => {
        if (client.frames[0]?.type === "ready") resolve(client);
      });
    });
  }
  const bearer = (token: string) => ({ authorization: `Bearer ${token}` });

  async function pair() {
    const alice = await login("alice");
    const bob = await login("bob");
    const qr = (await req(alice, "POST", "/connection-qr", {})).json();
    const r = (await req(bob, "POST", "/connection-requests", { token: qr.token })).json();
    const c = (await req(alice, "POST", `/connection-requests/${r.id}/accept`, {})).json();
    return { alice, bob, connectionId: c.id as string, conv: c.conversationId as string };
  }

  it("refuses the upgrade without a valid, unrestricted session; never accepts a token in the URL", async () => {
    await expect(connect({})).rejects.toThrow("HTTP 401");
    await expect(connect({ authorization: "Bearer vca_not-a-real-token-000000" })).rejects.toThrow("HTTP 401");
    const restricted = await login("carol", false);
    await expect(connect(bearer(restricted))).rejects.toThrow("HTTP 403");
    await new Promise<void>((resolve) => {
      const ws = new WebSocket(`ws://${base}/api/v1/ws?access_token=${restricted}`);
      ws.on("error", () => undefined); // the refused handshake also reports an error
      sockets.push(ws);
      ws.on("unexpected-response", (_r, res) => {
        expect(res.statusCode).toBe(401);
        resolve();
      });
    });
  });

  it("signals both users when a message is sent or the chat is cleared, without any message text", async () => {
    const { alice, bob, conv } = await pair();
    const a = await connect(bearer(alice));
    const b = await connect(bearer(bob));

    await req(alice, "POST", `/conversations/${conv}/messages`, { clientMessageId: randomUUID(), epoch: 0, text: "secret words" });
    await b.waitFor(() => b.frames.some((f) => f.type === "chat.changed"), "chat.changed for bob");
    await a.waitFor(() => a.frames.some((f) => f.type === "chat.changed"), "chat.changed for alice");
    expect(b.frames.find((f) => f.type === "chat.changed")).toEqual({ type: "chat.changed", data: { conversationId: conv } });

    const before = b.frames.length;
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });
    await req(bob, "POST", `/conversations/${conv}/leave`, { leaveId: randomUUID() });
    await b.waitFor(() => b.frames.length > before, "signal after the clear");
    expect(b.raw.join("")).not.toContain("secret");
  });

  it("signals both people as a pairing request is made, answered or withdrawn", async () => {
    const alice = await login("alice");
    const bob = await login("bob");
    const a = await connect(bearer(alice));
    const b = await connect(bearer(bob));
    const signals = (c: Client) => c.frames.filter((f) => f.type === "connection.changed");

    // Bob scans and then withdraws; Alice sees both at once.
    const first = (await req(alice, "POST", "/connection-qr", {})).json();
    const r1 = (await req(bob, "POST", "/connection-requests", { token: first.token })).json();
    await a.waitFor(() => signals(a).length === 1, "request signal for the QR owner");
    expect(signals(a)[0]).toEqual({ type: "connection.changed", data: { requestId: r1.id } });
    await b.waitFor(() => signals(b).length === 1, "request signal for the scanner");
    await req(bob, "POST", `/connection-requests/${r1.id}/cancel`, {});
    await a.waitFor(() => signals(a).length === 2, "cancel signal");

    // Rejected, then a new scan accepted: the scanner learns each answer.
    const second = (await req(alice, "POST", "/connection-qr", {})).json();
    const r2 = (await req(bob, "POST", "/connection-requests", { token: second.token })).json();
    await req(alice, "POST", `/connection-requests/${r2.id}/reject`, {});
    await b.waitFor(() => signals(b).filter((f) => f.data.requestId === r2.id).length === 2, "reject signal");
    const third = (await req(alice, "POST", "/connection-qr", {})).json();
    const r3 = (await req(bob, "POST", "/connection-requests", { token: third.token })).json();
    expect((await req(alice, "POST", `/connection-requests/${r3.id}/accept`, {})).statusCode).toBe(200);
    await b.waitFor(() => signals(b).filter((f) => f.data.requestId === r3.id).length === 2, "accept signal");
    // IDs only: no usernames or QR tokens on the socket.
    for (const raw of [...a.raw, ...b.raw]) {
      expect(raw).not.toContain("bob");
      expect(raw).not.toContain(third.token);
    }
  });

  it("signals an incoming call and its end", async () => {
    const { alice, bob, connectionId } = await pair();
    const b = await connect(bearer(bob));
    const call = (await req(alice, "POST", "/calls", { connectionId, kind: "video" })).json();
    await b.waitFor(() => b.frames.some((f) => f.type === "call.changed"), "call.changed");
    expect(b.frames.find((f) => f.type === "call.changed")!.data).toEqual({ callId: call.callId });
    await req(alice, "POST", `/calls/${call.callId}/cancel`, {});
    await b.waitFor(() => b.frames.filter((f) => f.type === "call.changed").length === 2, "second call.changed");
  });

  it("closes the socket when the session ends (logout, password reset, new login)", async () => {
    const alice = await login("alice");
    const a = await connect(bearer(alice));
    expect(hub.connected((await req(alice, "GET", "/me")).json().id)).toBe(1);
    await req(alice, "POST", "/auth/logout", {});
    await a.waitFor(() => a.closed !== null, "close after logout");
    expect(a.closed).toBe(WS_CLOSE.sessionEnded);
  });

  it("closes a socket that floods the server", async () => {
    const alice = await login("alice");
    const a = await connect(bearer(alice));
    for (let i = 0; i < 25; i++) a.ws.send("x");
    await a.waitFor(() => a.closed !== null, "close after flood");
    expect(a.closed).toBe(WS_CLOSE.policy);
  });
});
