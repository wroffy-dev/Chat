import type { FastifyServerOptions } from "fastify";
import type { Config } from "./config/env.js";

/**
 * Field names that must never reach the logs (docs/12-security-and-threat-model.md §3).
 * Each is redacted at the top level and one level deep, plus the request headers that carry credentials.
 */
const SENSITIVE_KEYS = [
  "password",
  "currentPassword",
  "newPassword",
  "token",
  "accessToken",
  "refreshToken",
  "livekitToken",
  "fcmToken",
  "credentials",
  "private_key",
  "secret",
  "text",
  "body",
  "lat",
  "lng",
  "latitude",
  "longitude",
  "databaseUrl",
  "DATABASE_URL",
];

export const REDACT_PATHS = [
  "req.headers.authorization",
  "req.headers.cookie",
  'res.headers["set-cookie"]',
  ...SENSITIVE_KEYS,
  ...SENSITIVE_KEYS.map((k) => `*.${k}`),
];

type LoggerOptions = Exclude<FastifyServerOptions["logger"], boolean | undefined>;

export function buildLoggerOptions(config: Pick<Config, "LOG_LEVEL" | "NODE_ENV">): LoggerOptions {
  return {
    level: config.LOG_LEVEL,
    redact: { paths: REDACT_PATHS, censor: "[REDACTED]" },
    serializers: {
      // Log only what is needed to trace a request. The query string is dropped (it must never carry
      // secrets, but this keeps a mistake out of the logs) and client IPs are not logged routinely.
      req: (req: { method: string; url: string; id: string }) => ({
        id: req.id,
        method: req.method,
        path: req.url.split("?")[0],
      }),
      res: (res: { statusCode: number }) => ({ statusCode: res.statusCode }),
    },
  };
}
