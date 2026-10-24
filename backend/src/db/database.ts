import { readdir } from "node:fs/promises";
import { PrismaPg } from "@prisma/adapter-pg";
import { PrismaClient } from "../generated/prisma/client.js";
import type { Config } from "../config/env.js";

export type ReadinessProblem = "database_unreachable" | "migrations_pending" | "migration_check_failed";

export interface Readiness {
  ready: boolean;
  problems: ReadinessProblem[];
  pendingMigrations: string[];
}

/** The database operations the HTTP layer depends on. Lets tests substitute a fake. */
export interface Database {
  checkReadiness(timeoutMs: number): Promise<Readiness>;
  close(): Promise<void>;
}

/** Migration folders shipped with this build (Prisma names them `<timestamp>_<name>`). */
export async function listLocalMigrations(dir: string): Promise<string[]> {
  try {
    const entries = await readdir(dir, { withFileTypes: true });
    return entries.filter((e) => e.isDirectory()).map((e) => e.name).sort();
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw err;
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  let timer: NodeJS.Timeout | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timed out after ${ms} ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

export class PrismaDatabase implements Database {
  readonly prisma: PrismaClient;

  constructor(
    config: Pick<Config, "DATABASE_URL" | "DATABASE_POOL_MAX">,
    private readonly migrationsDir: string,
  ) {
    const adapter = new PrismaPg({
      connectionString: config.DATABASE_URL,
      max: config.DATABASE_POOL_MAX,
      connectionTimeoutMillis: 5000,
      idleTimeoutMillis: 30_000,
    });
    // No query logging: queries can carry message text and other sensitive parameters.
    this.prisma = new PrismaClient({ adapter, log: [] });
  }

  async checkReadiness(timeoutMs: number): Promise<Readiness> {
    try {
      await withTimeout(this.prisma.$queryRaw`SELECT 1`, timeoutMs);
    } catch {
      return { ready: false, problems: ["database_unreachable"], pendingMigrations: [] };
    }

    try {
      const local = await listLocalMigrations(this.migrationsDir);
      if (local.length === 0) return { ready: true, problems: [], pendingMigrations: [] };

      const applied = await withTimeout(this.appliedMigrations(), timeoutMs);
      const pending = local.filter((name) => !applied.has(name));
      return pending.length === 0
        ? { ready: true, problems: [], pendingMigrations: [] }
        : { ready: false, problems: ["migrations_pending"], pendingMigrations: pending };
    } catch {
      return { ready: false, problems: ["migration_check_failed"], pendingMigrations: [] };
    }
  }

  private async appliedMigrations(): Promise<Set<string>> {
    const table = await this.prisma.$queryRaw<{ exists: boolean }[]>`
      SELECT to_regclass('_prisma_migrations') IS NOT NULL AS "exists"`;
    if (!table[0]?.exists) return new Set();
    const rows = await this.prisma.$queryRaw<{ migration_name: string }[]>`
      SELECT migration_name FROM _prisma_migrations
      WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL`;
    return new Set(rows.map((r) => r.migration_name));
  }

  async close(): Promise<void> {
    await this.prisma.$disconnect();
  }
}
