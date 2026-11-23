import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { ChatService } from "../src/chat/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { ProfileService } from "../src/profile/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

describe.skipIf(!TEST_DB_URL)("avatars and nicknames (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let app: FastifyInstance;
  let ip = 0;

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections: new ConnectionService(database.prisma),
      profile: new ProfileService(database.prisma, new ChatService(database.prisma)),
      logger: false,
    });
    await app.ready();
  });
  afterEach(async () => {
    await app.close();
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
  const req = (token: string, method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(payload ? { payload } : {}) });

  async function connectUsers(a: string, b: string) {
    const ua = (await database.prisma.user.findUnique({ where: { username: a } }))!.id;
    const ub = (await database.prisma.user.findUnique({ where: { username: b } }))!.id;
    // connections_ordered_pair: user_a_id < user_b_id. The conversation makes it a chat (as a QR redeem does).
    const [userAId, userBId] = [ua, ub].sort();
    const c = await database.prisma.connection.create({ data: { userAId: userAId!, userBId: userBId! } });
    await database.prisma.conversation.create({
      data: { connectionId: c.id, participants: { create: [{ userId: ua }, { userId: ub }] } },
    });
    return c.id;
  }

  it("sets and clears a built-in avatar, shown to connected people", async () => {
    const alice = await login("alice");
    const bob = await login("bob");
    await connectUsers("alice", "bob");
    expect((await req(alice, "GET", "/me")).json().avatar).toBeNull();
    const set = await req(alice, "PUT", "/me/avatar", { avatar: "fox" });
    expect(set.statusCode).toBe(200);
    expect(set.json()).toEqual({ avatar: "fox" });
    expect((await req(alice, "GET", "/me")).json().avatar).toBe("fox");
    expect((await req(bob, "GET", "/connections")).json().items[0].peer).toMatchObject({ username: "alice", avatar: "fox" });
    expect((await req(alice, "PUT", "/me/avatar", { avatar: "photo.jpg" })).statusCode).toBe(400);
    expect((await req(alice, "PUT", "/me/avatar", { avatar: null })).json()).toEqual({ avatar: null });
  });

  it("gives a connected person a nickname only the setter sees, with a line in the chat", async () => {
    const alice = await login("alice");
    const bob = await login("bob");
    await login("carol");
    const connectionId = await connectUsers("alice", "bob");

    const set = await req(alice, "PUT", `/connections/${connectionId}/nickname`, { nickname: "  Bobby  " });
    expect(set.statusCode).toBe(200);
    expect(set.json()).toEqual({ nickname: "Bobby" });
    expect((await req(alice, "GET", "/connections")).json().items[0]).toMatchObject({ nickname: "Bobby", peer: { username: "bob" } });
    expect((await req(bob, "GET", "/connections")).json().items[0].nickname).toBeNull();

    // Renamed again, then removed: each change is one chat line from Alice; a repeat changes nothing.
    await req(alice, "PUT", `/connections/${connectionId}/nickname`, { nickname: "Bob ji" });
    await req(alice, "PUT", `/connections/${connectionId}/nickname`, { nickname: "Bob ji" });
    await req(alice, "PUT", `/connections/${connectionId}/nickname`, { nickname: null });
    const lines = await database.prisma.message.findMany({ where: { kind: "nickname" }, orderBy: { seq: "asc" } });
    expect(lines.map((m) => JSON.parse(m.body))).toEqual([
      { from: "bob", to: "Bobby" },
      { from: "Bobby", to: "Bob ji" },
      { from: "Bob ji", to: "bob" },
    ]);
    const aliceId = (await database.prisma.user.findUnique({ where: { username: "alice" } }))!.id;
    expect(lines.every((m) => m.senderUserId === aliceId)).toBe(true);
    expect((await req(alice, "GET", "/connections")).json().items[0].nickname).toBeNull();

    // Someone else's connection, a bad id and a too-long name are refused.
    const carol = await login("carol2");
    expect((await req(carol, "PUT", `/connections/${connectionId}/nickname`, { nickname: "x" })).statusCode).toBe(404);
    expect((await req(alice, "PUT", "/connections/not-a-uuid/nickname", { nickname: "x" })).statusCode).toBe(400);
    expect((await req(alice, "PUT", `/connections/${connectionId}/nickname`, { nickname: "x".repeat(41) })).statusCode).toBe(400);
  });
});
