import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
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
      notes: new NoteService(database.prisma),
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

  it("keeps notes private: another user cannot list, read, edit or delete them", async () => {
    const alice = await login("alice");
    const bob = await login("bob");
    const note = (await req(alice, "POST", "/notes", { title: "Private", body: "secret" })).json();

    expect((await req(bob, "GET", "/notes")).json().items).toEqual([]);
    expect((await req(bob, "PATCH", `/notes/${note.id}`, { body: "changed" })).statusCode).toBe(404);
    expect((await req(bob, "DELETE", `/notes/${note.id}`)).statusCode).toBe(404);
    expect((await req(alice, "GET", "/notes")).json().items[0]).toMatchObject({ body: "secret" });
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
