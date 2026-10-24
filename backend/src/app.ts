import { randomUUID } from "node:crypto";
import Fastify, { type FastifyInstance, type FastifyServerOptions } from "fastify";
import type { Config } from "./config/env.js";
import type { Database } from "./db/database.js";
import { buildLoggerOptions } from "./logger.js";
import { registerErrorHandling } from "./plugins/error-handler.js";
import { healthRoutes } from "./routes/health.js";

export interface AppDeps {
  config: Config;
  database: Database;
  /** Overrides the logger (tests pass `false` or a capturing stream). */
  logger?: FastifyServerOptions["logger"];
}

export function buildApp({ config, database, logger }: AppDeps): FastifyInstance {
  const app = Fastify({
    logger: logger ?? buildLoggerOptions(config),
    // Client-supplied request IDs are not trusted; every request gets a fresh one.
    genReqId: () => randomUUID(),
    requestIdHeader: false,
    trustProxy: config.TRUST_PROXY.length > 0 ? config.TRUST_PROXY : false,
    bodyLimit: config.BODY_LIMIT_BYTES,
    return503OnClosing: true,
    ajv: {
      customOptions: {
        // Reject unknown fields instead of silently stripping them (docs/14-api-contracts.md §1).
        removeAdditional: false,
        allErrors: false,
      },
    },
  });

  app.addHook("onSend", async (request, reply) => {
    reply.header("X-Request-Id", request.id);
    reply.header("X-Content-Type-Options", "nosniff");
    reply.header("Cache-Control", "no-store");
  });

  registerErrorHandling(app);

  app.register(healthRoutes, { database, readinessTimeoutMs: config.READINESS_DB_TIMEOUT_MS });

  app.addHook("onClose", async () => {
    await database.close();
  });

  return app;
}
