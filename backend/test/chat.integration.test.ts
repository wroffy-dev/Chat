import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { ChatService } from "../src/chat/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { PushService, type PushPayload, type PushTransport } from "../src/push/service.js";
import { captureLogs, resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

class RecordingTransport implements PushTransport {
  sent: { token: string; payload: PushPayload; high: boolean }[] = [];
  async send(token: string, payload: PushPayload, high: boolean) {
    this.sent.push({ token, payload, high });
  }
}

describe.skipIf(!TEST_DB_URL)("chat (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let chat: ChatService;
  let transport: RecordingTransport;
  let app: FastifyInstance;
  let logs: string[];
  let ip = 0;
  let clockMs: number | null = null;
  const now = () => (clockMs === null ? new Date() : new Date(clockMs));
  const advance = (ms: number) => {
    clockMs = (clockMs ?? Date.now()) + ms;
  };

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
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
    chat = new ChatService(database.prisma, now, push);
    await app?.close();
    const captured = captureLogs(config);
    logs = captured.lines;
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections: new ConnectionService(database.prisma),
      chat,
      push,
      logger: captured.logger,
    });
    await app.ready();
  });

  async function login(username: string) {
    const { temporaryPassword } = await auth.createUser(username, { type: "system", id: null });
    const remoteAddress = `198.51.100.${++ip % 250}`;
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

  /** Two paired users; returns tokens, user ids and the conversation id from /me. */
  async function pair() {
    const alice = await login("alice");
    const bob = await login("bob");
    const qr = (await req(alice, "POST", "/connection-qr", {})).json();
    const r = (await req(bob, "POST", "/connection-requests", { token: qr.token })).json();
    const accepted = (await req(alice, "POST", `/connection-requests/${r.id}/accept`, {})).json();
    const me = (await req(alice, "GET", "/me")).json();
    const meBob = (await req(bob, "GET", "/me")).json();
    expect(me.connection.conversationId).toBe(accepted.conversationId);
    expect(meBob.connection.conversationId).toBe(accepted.conversationId);
    return { alice, bob, aliceId: me.id as string, bobId: meBob.id as string, conv: accepted.conversationId as string };
  }

  const send = (token: string, conv: string, text: string, epoch = 0, clientMessageId = randomUUID()) =>
    req(token, "POST", `/conversations/${conv}/messages`, { clientMessageId, epoch, text });
  const history = async (token: string, conv: string, query = "") => (await req(token, "GET", `/conversations/${conv}/messages${query}`)).json();
  const texts = (h: { items: { text: string }[] }) => h.items.map((m) => m.text);
  const leave = (token: string, conv: string, leaveId = randomUUID()) => req(token, "POST", `/conversations/${conv}/leave`, { leaveId });

  it("sends, lists and retries idempotently; text is trimmed and validated", async () => {
    const { alice, bob, aliceId, conv } = await pair();
    const enter = (await req(alice, "POST", `/conversations/${conv}/enter`, {})).json();
    expect(enter).toMatchObject({ leaseTtlSeconds: 60, heartbeatSeconds: 20, epoch: 0 });

    const cmid = randomUUID();
    const first = await send(alice, conv, "  hello  ", 0, cmid);
    expect(first.statusCode).toBe(201);
    const retry = await send(alice, conv, "  hello  ", 0, cmid);
    expect(retry.statusCode).toBe(200);
    expect(retry.json().id).toBe(first.json().id);

    const h = await history(bob, conv);
    expect(h).toMatchObject({ epoch: 0, cleared: false, hasMore: false });
    expect(h.items).toEqual([expect.objectContaining({ seq: 1, senderId: aliceId, text: "hello" })]);
    expect((await history(bob, conv, "?afterSeq=1")).items).toEqual([]);

    expect((await send(alice, conv, "   ")).statusCode).toBe(400);
    expect((await send(alice, conv, "a\u0000b")).statusCode).toBe(400);
    expect((await send(alice, conv, "😀".repeat(4000))).statusCode).toBe(201); // 4,000 code points
    const tooLong = await send(alice, conv, "x".repeat(4001));
    expect(tooLong.statusCode).toBe(422);
    expect(tooLong.json().error.code).toBe("MESSAGE_TOO_LONG");
  });

  it("the chat list counts unread messages from the peer until they are read (D-21)", async () => {
    const { alice, bob, conv } = await pair();
    await send(alice, conv, "one");
    await send(alice, conv, "two");
    const bobList = (await req(bob, "GET", "/connections")).json().items;
    expect(bobList).toEqual([expect.objectContaining({ conversationId: conv, unreadCount: 2, lastMessageAt: expect.any(String) })]);
    expect((await req(alice, "GET", "/connections")).json().items[0].unreadCount).toBe(0);
    expect((await req(bob, "POST", `/conversations/${conv}/read`, { seq: 2 })).statusCode).toBe(204);
    expect((await req(bob, "GET", "/connections")).json().items[0].unreadCount).toBe(0);
  });

  it("concurrent sends get distinct, gapless sequence numbers", async () => {
    const { alice, bob, conv } = await pair();
    const results = await Promise.all(Array.from({ length: 10 }, (_, i) => send(i % 2 ? alice : bob, conv, `m${i}`)));
    expect(results.map((r) => r.statusCode)).toEqual(Array(10).fill(201));
    expect(results.map((r) => r.json().seq).sort((a, b) => a - b)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
  });

  it("refuses non-participants with 404", async () => {
    const { alice } = await pair();
    const other = randomUUID();
    expect((await req(alice, "POST", `/conversations/${other}/enter`, {})).statusCode).toBe(404);
    expect((await send(alice, other, "hi")).statusCode).toBe(404);
    expect((await req(alice, "GET", `/conversations/${other}/messages`)).statusCode).toBe(404);
    expect((await req(alice, "POST", `/conversations/${other}/read`, { seq: 1 })).statusCode).toBe(404);
  });

  it("D-01 option D: an exit deletes only messages their recipient has seen", async () => {
    const { alice, bob, conv } = await pair();
    await send(alice, conv, "a1"); // seq 1
    await send(alice, conv, "a2"); // seq 2
    await send(bob, conv, "b3"); // seq 3, not yet seen by alice

    // Bob displayed only the first message.
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });
    expect((await leave(alice, conv)).json()).toEqual({ epoch: 1 });

    const h = await history(bob, conv, "?afterSeq=3&epoch=0");
    expect(h.cleared).toBe(true);
    expect(texts(h)).toEqual(["a2", "b3"]);

    // Alice sees b3 and everything; Bob sees a2; next exit (by Bob) deletes the rest.
    await req(alice, "POST", `/conversations/${conv}/read`, { seq: 3 });
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 3 });
    expect((await leave(bob, conv)).json()).toEqual({ epoch: 2 });
    expect(texts(await history(alice, conv))).toEqual([]);
  });

  it("a read cursor never covers messages sent after it", async () => {
    const { alice, bob, conv } = await pair();
    await send(alice, conv, "seen");
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 999 }); // capped at last_seq = 1
    await send(alice, conv, "unseen", 0);
    await leave(alice, conv);
    expect(texts(await history(bob, conv))).toEqual(["unseen"]);
  });

  it("an exit that deletes nothing keeps the epoch; leave is idempotent by leaveId", async () => {
    const { alice, bob, conv } = await pair();
    await send(alice, conv, "m1");
    expect((await leave(bob, conv)).json()).toEqual({ epoch: 0 }); // nothing seen yet

    const leaveId = randomUUID();
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });
    expect((await leave(bob, conv, leaveId)).json()).toEqual({ epoch: 1 });
    await send(alice, conv, "m2", 1);
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 2 });
    // Retrying the same exit does not run the clear again.
    expect((await leave(bob, conv, leaveId)).json()).toEqual({ epoch: 1 });
    expect(texts(await history(alice, conv))).toEqual(["m2"]);
  });

  it("refuses sends composed before a clear, but a retry of a surviving message still succeeds", async () => {
    const { alice, bob, conv } = await pair();
    await send(alice, conv, "seen");
    const keep = randomUUID();
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });
    await send(alice, conv, "unseen", 0, keep);
    await leave(bob, conv); // deletes "seen", epoch → 1

    const stale = await send(alice, conv, "composed before the clear", 0);
    expect(stale.statusCode).toBe(409);
    expect(stale.json().error.code).toBe("CONVERSATION_CLEARED");
    // The lost-response retry of a message that still exists returns it rather than refusing.
    expect((await send(alice, conv, "unseen", 0, keep)).statusCode).toBe(200);
    expect(texts(await history(bob, conv))).toEqual(["unseen"]);
  });

  it("D-02: a lapsed presence lease counts as an exit", async () => {
    const { alice, bob, conv } = await pair();
    clockMs = Date.now();
    const lease = (await req(bob, "POST", `/conversations/${conv}/enter`, {})).json().leaseId;
    await send(alice, conv, "hello");
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });

    // Heartbeats every 20 s keep the lease alive.
    advance(40_000);
    expect((await req(bob, "POST", `/conversations/${conv}/heartbeat`, { leaseId: lease })).statusCode).toBe(204);
    advance(40_000);
    expect(await chat.sweep()).toEqual({ expiredLeases: 0, backstopDeleted: 0 });
    expect(texts(await history(alice, conv))).toEqual(["hello"]);

    // Bob's phone goes quiet (background, crash, no network): after 60 s the server exits for him.
    advance(61_000);
    expect(await chat.sweep()).toEqual({ expiredLeases: 1, backstopDeleted: 0 });
    expect(texts(await history(alice, conv))).toEqual([]);
    const hb = await req(bob, "POST", `/conversations/${conv}/heartbeat`, { leaseId: lease });
    expect(hb.statusCode).toBe(409);
    expect(hb.json().error.code).toBe("LEASE_EXPIRED");
  });

  it("D-02: a heartbeat on a lapsed but unswept lease applies the exit", async () => {
    const { alice, bob, conv } = await pair();
    clockMs = Date.now();
    const lease = (await req(bob, "POST", `/conversations/${conv}/enter`, {})).json().leaseId;
    await send(alice, conv, "hello");
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });
    advance(61_000);
    expect((await req(bob, "POST", `/conversations/${conv}/heartbeat`, { leaseId: lease })).statusCode).toBe(409);
    expect(texts(await history(alice, conv))).toEqual([]);
  });

  it("D-02 backstop: every message is deleted 24 h after it was sent, seen or not", async () => {
    const { alice, bob, conv } = await pair();
    clockMs = Date.now();
    await send(alice, conv, "old and unseen");
    advance(23 * 3600_000);
    await send(bob, conv, "newer", 0);
    advance(3600_000 + 1000);
    expect(await chat.sweep()).toEqual({ expiredLeases: 0, backstopDeleted: 1 });
    const h = await history(bob, conv, "?epoch=0");
    expect(h).toMatchObject({ epoch: 1, cleared: true });
    expect(texts(h)).toEqual(["newer"]);
  });

  it("pushes a content-free 'new message' only when the recipient is not in the chat, throttled", async () => {
    const { alice, bob, conv } = await pair();
    await req(bob, "PUT", "/devices/push-token", { fcmToken: "fcm-token-for-device-b-0123456789" });
    clockMs = Date.now();
    await send(alice, conv, "secret words one");
    await send(alice, conv, "secret words two"); // within a minute: no second push
    await new Promise((r) => setTimeout(r, 50));
    expect(transport.sent).toEqual([{ token: "fcm-token-for-device-b-0123456789", payload: { t: "msg" }, high: false }]);

    advance(61_000); // past the push throttle
    await req(bob, "POST", `/conversations/${conv}/enter`, {});
    await send(alice, conv, "while bob is in the chat", 0);
    await new Promise((r) => setTimeout(r, 50));
    expect(transport.sent).toHaveLength(1);
    expect(JSON.stringify(transport.sent)).not.toContain("secret");
  });

  it("limits sends to 30 per minute per user", async () => {
    const { alice, conv } = await pair();
    clockMs = Date.now();
    for (let i = 0; i < 30; i++) expect((await send(alice, conv, `m${i}`)).statusCode).toBe(201);
    const limited = await send(alice, conv, "one too many");
    expect(limited.statusCode).toBe(429);
    expect(limited.headers["retry-after"]).toBeDefined();
  });

  const react = (token: string, conv: string, messageId: string, emoji: string) =>
    req(token, "PUT", `/conversations/${conv}/messages/${messageId}/reaction`, { emoji });
  const unreact = (token: string, conv: string, messageId: string) => req(token, "DELETE", `/conversations/${conv}/messages/${messageId}/reaction`);

  it("D-25: one reaction per person per message; set, replace, remove; visible to both even when read incrementally", async () => {
    const { alice, bob, aliceId, bobId, conv } = await pair();
    const m1 = (await send(alice, conv, "first")).json().id as string;
    await send(alice, conv, "second");

    expect((await react(bob, conv, m1, "❤️")).statusCode).toBe(204);
    expect((await react(alice, conv, m1, "😂")).statusCode).toBe(204); // the sender may react to their own message
    // Read with afterSeq past both messages: the reactions still arrive.
    const later = await history(alice, conv, "?afterSeq=2&epoch=0");
    expect(later.items).toEqual([]);
    expect(later.reactions).toEqual(
      expect.arrayContaining([
        { messageId: m1, userId: bobId, emoji: "❤️" },
        { messageId: m1, userId: aliceId, emoji: "😂" },
      ]),
    );
    expect(later.reactions).toHaveLength(2);

    // Replace, then remove; skin tones and ZWJ sequences count as one emoji.
    expect((await react(bob, conv, m1, "👍🏽")).statusCode).toBe(204);
    expect((await history(bob, conv)).reactions).toContainEqual({ messageId: m1, userId: bobId, emoji: "👍🏽" });
    expect((await unreact(bob, conv, m1)).statusCode).toBe(204);
    expect((await unreact(bob, conv, m1)).statusCode).toBe(204); // idempotent
    expect((await history(bob, conv)).reactions).toEqual([{ messageId: m1, userId: aliceId, emoji: "😂" }]);
  });

  it("D-25: refuses anything but a single emoji, and messages that are gone or not in the conversation", async () => {
    const { alice, bob, conv } = await pair();
    const m1 = (await send(alice, conv, "hi")).json().id as string;
    for (const bad of ["😂😂", "ok", "❤", " 👍", ""]) {
      expect({ bad, status: (await react(bob, conv, m1, bad)).statusCode }).toEqual({ bad, status: 400 });
    }
    expect((await react(bob, conv, randomUUID(), "👍")).statusCode).toBe(404);
    expect((await react(bob, randomUUID(), m1, "👍")).statusCode).toBe(404);
  });

  it("D-25 with D-01: a reaction is deleted with its message, and a deleted message cannot be reacted to", async () => {
    const { alice, bob, conv } = await pair();
    const m1 = (await send(alice, conv, "seen soon")).json().id as string;
    await react(bob, conv, m1, "🙏");
    await req(bob, "POST", `/conversations/${conv}/read`, { seq: 1 });
    await leave(alice, conv);
    const h = await history(bob, conv);
    expect(h.items).toEqual([]);
    expect(h.reactions).toEqual([]);
    expect((await react(bob, conv, m1, "👍")).statusCode).toBe(404);
  });

  it("never writes message text to the logs", async () => {
    const { alice, bob, conv } = await pair();
    await send(alice, conv, "a very private sentence");
    await history(bob, conv);
    await send(alice, conv, "x".repeat(4001) + "private tail");
    expect(logs.length).toBeGreaterThan(0);
    expect(logs.join("\n")).not.toContain("private");
  });

  it("the history snapshot pages at 200 messages", async () => {
    const { alice, bob, conv } = await pair();
    const c = await database.prisma.conversation.findUniqueOrThrow({ where: { id: conv } });
    const sender = (await database.prisma.user.findFirstOrThrow({ where: { username: "alice" } })).id;
    await database.prisma.message.createMany({
      data: Array.from({ length: 205 }, (_, i) => ({ conversationId: c.id, seq: BigInt(i + 1), epoch: 0n, senderUserId: sender, clientMessageId: randomUUID(), body: `m${i + 1}` })),
    });
    await database.prisma.conversation.update({ where: { id: conv }, data: { lastSeq: 205n } });
    const page = await history(bob, conv);
    expect(page.items).toHaveLength(200);
    expect(page.hasMore).toBe(true);
    const rest = await history(bob, conv, "?afterSeq=200");
    expect(texts(rest)).toEqual(["m201", "m202", "m203", "m204", "m205"]);
    expect(rest.hasMore).toBe(false);
    void alice;
  });
});
