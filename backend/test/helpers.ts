import { Writable } from "node:stream";
import { loadConfig, type Config } from "../src/config/env.js";
import type { Database, Readiness } from "../src/db/database.js";
import { buildLoggerOptions } from "../src/logger.js";

export function testConfig(overrides: Record<string, string> = {}): Config {
  return loadConfig({
    NODE_ENV: "test",
    DATABASE_URL: "postgresql://user:test-only-password@localhost:5432/test",
    LOG_LEVEL: "info",
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
