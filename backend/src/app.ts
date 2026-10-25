import { randomUUID } from "node:crypto";
import rateLimit from "@fastify/rate-limit";
import websocket from "@fastify/websocket";
import Fastify, { type FastifyInstance, type FastifyServerOptions } from "fastify";
import type { AuthService } from "./auth/service.js";
import type { ConnectionService } from "./connections/service.js";
import type { CallService } from "./calls/service.js";
import type { ChatService } from "./chat/service.js";
import type { MediaGateway } from "./calls/media.js";
import type { PushService } from "./push/service.js";
import type { Config } from "./config/env.js";
import type { Database } from "./db/database.js";
import { buildLoggerOptions } from "./logger.js";
import { registerErrorHandling } from "./plugins/error-handler.js";
import { authRoutes } from "./routes/auth.js";
import { connectionRoutes } from "./routes/connections.js";
import { callRoutes, livekitWebhookRoutes } from "./routes/calls.js";
import { chatRoutes } from "./routes/chat.js";
import { deviceRoutes } from "./routes/devices.js";
import { realtimeRoutes } from "./routes/realtime.js";
import type { RealtimeHub } from "./realtime/hub.js";
import { healthRoutes } from "./routes/health.js";
import { AppError } from "./errors.js";

export interface AppDeps {
  config: Config;
  database: Database;
  /** App-user authentication. When omitted (some tests), the /api/v1 routes are not registered. */
  auth?: AuthService;
  connections?: ConnectionService;
  calls?: CallService;
  chat?: ChatService;
  /** LiveKit gateway; needed only to verify webhooks. */
  media?: MediaGateway | null;
  push?: PushService;
  /** Live change signals over GET /api/v1/ws. */
  realtime?: { hub: RealtimeHub; recheckMs?: number; pingMs?: number };
  /** Overrides the logger (tests pass `false` or a capturing stream). */
  logger?: FastifyServerOptions["logger"];
}

export function buildApp({ config, database, auth, connections, calls, chat, media, push, realtime, logger }: AppDeps): FastifyInstance {
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

  if (auth) {
    app.register(async (api) => {
      // Per-route limits only (in-memory store: a single backend instance, docs/02 ADR-07).
      await api.register(rateLimit, {
        global: false,
        errorResponseBuilder: (_req, context) =>
          new AppError(429, "RATE_LIMITED", "Too many requests. Try again later.", undefined, Math.ceil(context.ttl / 1000)),
      });
      await api.register(authRoutes, connections ? { auth, connections } : { auth });
      if (connections) await api.register(connectionRoutes, { auth, connections });
      if (calls) await api.register(callRoutes, { auth, calls });
      if (chat) await api.register(chatRoutes, { auth, chat });
      if (push) await api.register(deviceRoutes, { auth, push });
      if (realtime) {
        // 16 KB frames at most; the app sends nothing but pongs anyway.
        await api.register(websocket, { options: { maxPayload: 16_384 } });
        await api.register(realtimeRoutes, { auth, ...realtime });
      }
    }, { prefix: "/api/v1" });
  }

  if (calls && media) app.register(livekitWebhookRoutes, { calls, media });

  app.addHook("onClose", async () => {
    await database.close();
  });

  return app;
}
