import { mkdtemp, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { listLocalMigrations, PrismaDatabase } from "../src/db/database.js";
import { testConfig } from "./helpers.js";

// Runs only against a disposable database supplied via TEST_DATABASE_URL (never production).
const url = process.env["TEST_DATABASE_URL"];

describe.skipIf(!url)("PrismaDatabase against PostgreSQL", () => {
  let migrationsDir: string;

  beforeAll(async () => {
    migrationsDir = await mkdtemp(join(tmpdir(), "vc-migrations-"));
  });
  afterAll(async () => {
    await rm(migrationsDir, { recursive: true, force: true });
  });

  it("is ready when the database is reachable and no migrations exist", async () => {
    const db = new PrismaDatabase({ DATABASE_URL: url!, DATABASE_POOL_MAX: 2 }, migrationsDir);
    try {
      expect(await db.checkReadiness(2000)).toEqual({ ready: true, problems: [], pendingMigrations: [] });
    } finally {
      await db.close();
    }
  });

  it("reports migrations that exist locally but are not applied", async () => {
    await mkdir(join(migrationsDir, "20990101000000_not_applied"));
    const db = new PrismaDatabase({ DATABASE_URL: url!, DATABASE_POOL_MAX: 2 }, migrationsDir);
    try {
      const readiness = await db.checkReadiness(2000);
      expect(readiness.ready).toBe(false);
      expect(readiness.problems).toEqual(["migrations_pending"]);
      expect(readiness.pendingMigrations).toEqual(["20990101000000_not_applied"]);
    } finally {
      await db.close();
    }
  });

  it("serves /readyz from the real database", async () => {
    const empty = await mkdtemp(join(tmpdir(), "vc-migrations-empty-"));
    const config = testConfig({ DATABASE_URL: url! });
    const app = buildApp({ config, database: new PrismaDatabase(config, empty), logger: false });
    try {
      const res = await app.inject({ method: "GET", url: "/readyz" });
      expect(res.statusCode).toBe(200);
    } finally {
      await app.close();
      await rm(empty, { recursive: true, force: true });
    }
  });
});

describe("PrismaDatabase with an unreachable server", () => {
  it("reports database_unreachable within the timeout", async () => {
    // Port 1 on localhost: connection refused immediately, no external network involved.
    const db = new PrismaDatabase(
      { DATABASE_URL: "postgresql://u:test-only@127.0.0.1:1/none", DATABASE_POOL_MAX: 1 },
      "does-not-exist",
    );
    const started = Date.now();
    try {
      const readiness = await db.checkReadiness(1500);
      expect(readiness).toEqual({ ready: false, problems: ["database_unreachable"], pendingMigrations: [] });
      expect(Date.now() - started).toBeLessThan(3000);
    } finally {
      await db.close();
    }
  });
});

describe("listLocalMigrations", () => {
  it("returns an empty list when the directory does not exist", async () => {
    expect(await listLocalMigrations("/nonexistent/vc/migrations")).toEqual([]);
  });
});
