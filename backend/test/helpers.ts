import { Writable } from "node:stream";
import { loadConfig, type Config } from "../src/config/env.js";
import type { Database, Readiness } from "../src/db/database.js";
import { buildLoggerOptions } from "../src/logger.js";

export function testConfig(overrides: Record<string, string> = {}): Config {
  return loadConfig({
    NODE_ENV: "test",
    DATABASE_URL: "postgresql://user:test-only-password@localhost:5432/test",
    LOG_LEVEL: "info",
    // Test-only key (32 zero bytes) and cheap Argon2 settings to keep tests fast.
    ADMIN_TOTP_ENC_KEY: Buffer.alloc(32).toString("base64"),
    ARGON2_MEMORY_KIB: "1024",
    ...overrides,
  });
}

export class FakeDatabase implements Database {
  closed = false;
  constructor(public readiness: Readiness = { ready: true, problems: [], pendingMigrations: [] }) {}
  async checkReadiness(): Promise<Readiness> {
    return this.readiness;
  }
  async close(): Promise<void> {
    this.closed = true;
  }
}

/** A pino-compatible logger config that collects every log line, with production redaction rules. */
export function captureLogs(config: Config) {
  const lines: string[] = [];
  const stream = new Writable({
    write(chunk, _enc, cb) {
      lines.push(chunk.toString());
      cb();
    },
  });
  return { lines, logger: { ...buildLoggerOptions(config), stream } };
}

export const TEST_DB_URL = process.env["TEST_DATABASE_URL"];

/** Empties the Phase 2 tables. Only ever pointed at the disposable TEST_DATABASE_URL. */
export async function resetDatabase(prisma: { $executeRawUnsafe(sql: string): Promise<unknown> }) {
  await prisma.$executeRawUnsafe(
    'TRUNCATE "messages", "conversation_participants", "conversations", "call_diagnostics", "calls", "connections", "connection_requests", "qr_tokens", "audit_events", "admin_sessions", "admin_accounts", "sessions", "users" RESTART IDENTITY CASCADE',
  );
}
