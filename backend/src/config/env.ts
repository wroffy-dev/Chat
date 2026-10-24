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
  // 32 random bytes, base64. Encrypts admin TOTP secrets at rest.
  ADMIN_TOTP_ENC_KEY: z
    .string()
    .refine((v) => Buffer.from(v, "base64").length === 32, "must be 32 bytes, base64-encoded"),

  // Argon2id cost (docs/04-accounts-and-authentication.md §3). Tests may lower these.
  ARGON2_MEMORY_KIB: z.coerce.number().int().min(1024).max(1_048_576).default(47_104),
  ARGON2_TIME_COST: z.coerce.number().int().min(1).max(10).default(1),
}).superRefine((env, ctx) => {
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
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const result = envSchema.safeParse(env);
  if (!result.success) {
    throw new ConfigError(
      result.error.issues.map((issue) => `${issue.path.join(".") || "(root)"}: ${issue.message}`),
    );
  }
  return result.data;
}
