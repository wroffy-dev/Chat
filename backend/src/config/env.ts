import { z } from "zod";

const LOG_LEVELS = ["fatal", "error", "warn", "info", "debug", "trace", "silent"] as const;

const port = z.coerce.number().int().min(1).max(65535);

const envSchema = z.object({
  NODE_ENV: z.enum(["development", "test", "production"]).default("development"),
  HOST: z.string().min(1).default("0.0.0.0"),
  PORT: port.default(3000),
  LOG_LEVEL: z.enum(LOG_LEVELS).default("info"),
  DATABASE_URL: z
    .string()
    .min(1)
    .refine((v) => /^postgres(ql)?:\/\//.test(v), "must be a postgres:// or postgresql:// URL"),
  DATABASE_POOL_MAX: z.coerce.number().int().min(1).max(100).default(10),
  // Comma-separated IPs/CIDRs of trusted reverse proxies (Traefik's Docker network in production).
  // Unset means X-Forwarded-For is ignored, so clients cannot spoof their address.
  TRUST_PROXY: z
    .string()
    .optional()
    .transform((v) => (v ? v.split(",").map((s) => s.trim()).filter(Boolean) : [])),
  BODY_LIMIT_BYTES: z.coerce.number().int().min(1024).max(1_048_576).default(65_536),
  SHUTDOWN_TIMEOUT_MS: z.coerce.number().int().min(1000).max(60_000).default(10_000),
  READINESS_DB_TIMEOUT_MS: z.coerce.number().int().min(100).max(30_000).default(2000),
  MIGRATIONS_DIR: z.string().min(1).default("prisma/migrations"),

  // Admin Console listener (docs/05-admin-console.md §2). Bound to loopback; reached via SSH tunnel.
  ADMIN_HOST: z.string().min(1).default("127.0.0.1"),
  ADMIN_PORT: port.default(3001),
  // 32 random bytes, base64. Encrypts admin TOTP secrets at rest. Required by the server and the
  // admin CLI commands (requireAdminKey); maintenance jobs run without it, so they never hold it.
  ADMIN_TOTP_ENC_KEY: z
    .string()
    .refine((v) => Buffer.from(v, "base64").length === 32, "must be 32 bytes, base64-encoded")
    .optional(),

  // Argon2id cost (docs/04-accounts-and-authentication.md §3). Tests may lower these.
  ARGON2_MEMORY_KIB: z.coerce.number().int().min(1024).max(1_048_576).default(47_104),
  ARGON2_TIME_COST: z.coerce.number().int().min(1).max(10).default(1),

  // LiveKit (docs/08-calling.md, docs/16 §4). All four or none: without them calling returns
  // 503 MEDIA_UNAVAILABLE and everything else keeps working.
  LIVEKIT_PUBLIC_URL: z.string().regex(/^wss?:\/\//, "must be a ws:// or wss:// URL").optional(),
  LIVEKIT_API_URL: z.string().regex(/^https?:\/\//, "must be an http(s):// URL").optional(),
  LIVEKIT_API_KEY: z.string().min(1).optional(),
  LIVEKIT_API_SECRET: z.string().min(32, "must be at least 32 characters").optional(),
  CALL_RING_TIMEOUT_SECONDS: z.coerce.number().int().min(20).max(90).default(45),

  // Firebase Cloud Messaging service-account JSON (mounted file, never committed). Optional:
  // without it no pushes are sent and calls ring only while the app is open.
  FCM_CREDENTIALS_FILE: z.string().min(1).optional(),
  // Status file written by deploy/backup/backup.sh after each successful backup (read-only mount);
  // shown on the Admin Console health page.
  BACKUP_STATUS_FILE: z.string().min(1).optional(),

  // In-app updates (docs/16 §9): a writable directory for the latest APK, and the token the APK
  // build workflow uploads with. Without both, the app offers no download.
  APP_UPDATES_DIR: z.string().min(1).optional(),
  APP_UPLOAD_TOKEN: z.string().min(32, "must be at least 32 characters").optional(),
}).superRefine((env, ctx) => {
  const livekit = [env.LIVEKIT_PUBLIC_URL, env.LIVEKIT_API_URL, env.LIVEKIT_API_KEY, env.LIVEKIT_API_SECRET];
  if (livekit.some(Boolean) && !livekit.every(Boolean)) {
    ctx.addIssue({ code: "custom", path: ["LIVEKIT_API_KEY"], message: "set all of LIVEKIT_PUBLIC_URL, LIVEKIT_API_URL, LIVEKIT_API_KEY, LIVEKIT_API_SECRET, or none" });
  }
  if (env.NODE_ENV === "production" && env.LIVEKIT_PUBLIC_URL?.startsWith("ws://")) {
    ctx.addIssue({ code: "custom", path: ["LIVEKIT_PUBLIC_URL"], message: "must use wss:// in production" });
  }
  if (env.NODE_ENV === "production" && env.ARGON2_MEMORY_KIB < 19_456) {
    ctx.addIssue({ code: "custom", path: ["ARGON2_MEMORY_KIB"], message: "must be at least 19456 in production" });
  }
});

export type Config = z.infer<typeof envSchema>;

export class ConfigError extends Error {
  constructor(public readonly problems: string[]) {
    super(`Invalid configuration:\n${problems.map((p) => `  - ${p}`).join("\n")}`);
    this.name = "ConfigError";
  }
}

/**
 * Validates environment variables. Error messages name the variable and the rule only —
 * never the supplied value, which may be a secret (e.g. a database URL with a password).
 */
/** The admin TOTP encryption key, for the processes that need it (server, admin CLI commands). */
export function requireAdminKey(config: Config): string {
  if (!config.ADMIN_TOTP_ENC_KEY) throw new ConfigError(["ADMIN_TOTP_ENC_KEY: required (32 bytes, base64)"]);
  return config.ADMIN_TOTP_ENC_KEY;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  // An empty variable means "not set": compose passes optional ones through as "" (`${VAR:-}`).
  const set = Object.fromEntries(Object.entries(env).filter(([, v]) => v !== ""));
  const result = envSchema.safeParse(set);
  if (!result.success) {
    throw new ConfigError(
      result.error.issues.map((issue) => `${issue.path.join(".") || "(root)"}: ${issue.message}`),
    );
  }
  return result.data;
}
