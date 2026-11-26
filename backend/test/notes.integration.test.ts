import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { NoteService } from "../src/notes/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

describe.skipIf(!TEST_DB_URL)("notes (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let app: FastifyInstance;
  let ip = 0;
  /** Live "notes changed" signals the service sent, as [user ids, note id]. */
  let signals: [string[], string][] = [];

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
    signals = [];
    const events = { publish: (ids: readonly string[], e: { type: string; noteId?: string }) => void signals.push([[...ids].sort(), e.noteId ?? ""]) };
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      notes: new NoteService(database.prisma, events),
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
  const req = (token: string, method: "GET" | "POST" | "PATCH" | "DELETE", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(payload ? { payload } : {}) });

  it("creates, lists newest first, edits and deletes a user's own notes", async () => {
    const alice = await login("alice");
    const first = await req(alice, "POST", "/notes", { title: "Shopping", body: "milk, eggs" });
    expect(first.statusCode).toBe(201);
    expect(first.json()).toMatchObject({ title: "Shopping", body: "milk, eggs" });
    const second = (await req(alice, "POST", "/notes", { body: "no title" })).json();
    expect(second.title).toBe("");

    let items = (await req(alice, "GET", "/notes")).json().items;
    expect(items.map((n: { id: string }) => n.id)).toEqual([second.id, first.json().id]);

    const edited = await req(alice, "PATCH", `/notes/${first.json().id}`, { body: "milk, eggs, bread" });
    expect(edited.statusCode).toBe(200);
    expect(edited.json()).toMatchObject({ title: "Shopping", body: "milk, eggs, bread" });
    // The edited note moves to the top.
    items = (await req(alice, "GET", "/notes")).json().items;
    expect(items[0].id).toBe(first.json().id);

    expect((await req(alice, "DELETE", `/notes/${second.id}`)).statusCode).toBe(204);
    items = (await req(alice, "GET", "/notes")).json().items;
    expect(items).toHaveLength(1);
    expect((await req(alice, "DELETE", `/notes/${second.id}`)).statusCode).toBe(404);
  });

  async function connectUsers(a: string, b: string) {
    const ua = (await database.prisma.user.findUnique({ where: { username: a } }))!.id;
    const ub = (await database.prisma.user.findUnique({ where: { username: b } }))!.id;
    // connections_ordered_pair: user_a_id < user_b_id.
    const [userAId, userBId] = [ua, ub].sort();
    await database.prisma.connection.create({ data: { userAId: userAId!, userBId: userBId! } });
  }

  it("hides notes from people the writer is not connected with", async () => {
    const alice = await login("alice");
    const bob = await login("bob");
    const note = (await req(alice, "POST", "/notes", { title: "Private", body: "secret" })).json();

    expect((await req(bob, "GET", "/notes")).json().items).toEqual([]);
    expect((await req(bob, "PATCH", `/notes/${note.id}`, { body: "changed" })).statusCode).toBe(404);
    expect((await req(bob, "DELETE", `/notes/${note.id}`)).statusCode).toBe(404);
    expect((await req(bob, "POST", `/notes/${note.id}/lines`, { body: "hi" })).statusCode).toBe(404);
    expect((await req(alice, "GET", "/notes")).json().items[0]).toMatchObject({ body: "secret", mine: true, authorUsername: "alice", lines: [] });
  });

  it("shares notes with connected people, who can add lines but not edit or delete the note", async () => {
    const alice = await login("alice");
    const bob = await login("bob");
    const carol = await login("carol");
    await connectUsers("alice", "bob");
    const note = (await req(alice, "POST", "/notes", { title: "Trip", body: "tickets" })).json();
    const other = (await req(alice, "POST", "/notes", { body: "newer" })).json();

    const seen = (await req(bob, "GET", "/notes")).json().items;
    expect(seen).toHaveLength(2);
    expect(seen.find((n: { id: string }) => n.id === note.id)).toMatchObject({ mine: false, authorUsername: "alice", body: "tickets" });
    expect((await req(carol, "GET", "/notes")).json().items).toEqual([]);

    // Bob cannot change or delete Alice's note.
    expect((await req(bob, "PATCH", `/notes/${note.id}`, { body: "changed" })).statusCode).toBe(404);
    expect((await req(bob, "DELETE", `/notes/${note.id}`)).statusCode).toBe(404);

    // Bob adds a line; the note moves to the top for both, and both open apps are told at once (live notes).
    signals = [];
    const added = await req(bob, "POST", `/notes/${note.id}/lines`, { body: "hotel booked" });
    expect(added.statusCode).toBe(201);
    const ids = [(await database.prisma.user.findUnique({ where: { username: "alice" } }))!.id, (await database.prisma.user.findUnique({ where: { username: "bob" } }))!.id].sort();
    await vi.waitFor(() => expect(signals).toContainEqual([ids, note.id]));
    expect(added.json().lines).toMatchObject([{ body: "hotel booked", authorUsername: "bob", mine: true }]);
    const forAlice = (await req(alice, "GET", "/notes")).json().items;
    expect(forAlice.map((n: { id: string }) => n.id)).toEqual([note.id, other.id]);
    expect(forAlice[0].lines).toMatchObject([{ body: "hotel booked", authorUsername: "bob", mine: false }]);
    const lineId = forAlice[0].lines[0].id as string;

    // Alice's own line; Bob may not remove it, but Alice (the writer) may remove Bob's.
    const mine = (await req(alice, "POST", `/notes/${note.id}/lines`, { body: "thanks" })).json().lines[1];
    expect((await req(bob, "DELETE", `/notes/${note.id}/lines/${mine.id}`)).statusCode).toBe(404);
    expect((await req(carol, "DELETE", `/notes/${note.id}/lines/${lineId}`)).statusCode).toBe(404);
    expect((await req(alice, "DELETE", `/notes/${note.id}/lines/${lineId}`)).statusCode).toBe(204);
    expect((await req(bob, "GET", "/notes")).json().items[0].lines).toMatchObject([{ body: "thanks", authorUsername: "alice" }]);

    // Blank or too-long lines are refused.
    expect((await req(bob, "POST", `/notes/${note.id}/lines`, { body: "   " })).statusCode).toBe(400);
    expect((await req(bob, "POST", `/notes/${note.id}/lines`, { body: "x".repeat(2_001) })).statusCode).toBe(400);

    // Deleting the note deletes its lines.
    expect((await req(alice, "DELETE", `/notes/${note.id}`)).statusCode).toBe(204);
    expect(await database.prisma.noteLine.count()).toBe(0);
  });

  it("rejects missing sign-in, too-long text and empty edits", async () => {
    const alice = await login("alice");
    expect((await app.inject({ method: "GET", url: "/api/v1/notes" })).statusCode).toBe(401);
    expect((await req(alice, "POST", "/notes", { title: "x".repeat(201) })).statusCode).toBe(400);
    expect((await req(alice, "POST", "/notes", { body: "x".repeat(20_001) })).statusCode).toBe(400);
    expect((await req(alice, "POST", "/notes", { colour: "red" })).statusCode).toBe(400);
    const note = (await req(alice, "POST", "/notes", {})).json();
    expect((await req(alice, "PATCH", `/notes/${note.id}`, {})).statusCode).toBe(400);
    expect((await req(alice, "PATCH", "/notes/not-a-uuid", { body: "x" })).statusCode).toBe(400);
  });
});
