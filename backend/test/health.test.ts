import { afterEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { FakeDatabase, testConfig } from "./helpers.js";

let app: FastifyInstance | undefined;
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("health endpoints", () => {
  it("/healthz reports liveness", async () => {
    app = buildApp({ config: testConfig(), database: new FakeDatabase(), logger: false });
    const res = await app.inject({ method: "GET", url: "/healthz" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "ok" });
    expect(res.headers["x-request-id"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(res.headers["cache-control"]).toBe("no-store");
  });

  it("/readyz is 200 when the database is ready", async () => {
    app = buildApp({ config: testConfig(), database: new FakeDatabase(), logger: false });
    const res = await app.inject({ method: "GET", url: "/readyz" });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ status: "ready" });
  });

  it("/readyz is 503 without leaking details when the database is unreachable", async () => {
    const db = new FakeDatabase({ ready: false, problems: ["database_unreachable"], pendingMigrations: [] });
    app = buildApp({ config: testConfig(), database: db, logger: false });
    const res = await app.inject({ method: "GET", url: "/readyz" });
    expect(res.statusCode).toBe(503);
    expect(res.json()).toEqual({ status: "not_ready" });
  });

  it("/readyz is 503 when migrations are pending", async () => {
    const db = new FakeDatabase({ ready: false, problems: ["migrations_pending"], pendingMigrations: ["20260101_init"] });
    app = buildApp({ config: testConfig(), database: db, logger: false });
    const res = await app.inject({ method: "GET", url: "/readyz" });
    expect(res.statusCode).toBe(503);
    expect(res.body).not.toContain("20260101_init");
  });

  it("does not trust client-supplied request IDs", async () => {
    app = buildApp({ config: testConfig(), database: new FakeDatabase(), logger: false });
    const res = await app.inject({ method: "GET", url: "/healthz", headers: { "x-request-id": "attacker-chosen" } });
    expect(res.headers["x-request-id"]).not.toBe("attacker-chosen");
  });

  it("closes the database when the app closes", async () => {
    const db = new FakeDatabase();
    const local = buildApp({ config: testConfig(), database: db, logger: false });
    await local.ready();
    await local.close();
    expect(db.closed).toBe(true);
  });
});
