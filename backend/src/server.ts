import { resolve } from "node:path";
import { buildAdminApp } from "./admin/app.js";
import { AdminService } from "./admin/service.js";
import { buildApp } from "./app.js";
import { PasswordHasher } from "./auth/passwords.js";
import { AuthService } from "./auth/service.js";
import { ConnectionService } from "./connections/service.js";
import { CallService } from "./calls/service.js";
import { ChatService } from "./chat/service.js";
import { RealtimeHub } from "./realtime/hub.js";
import { RetentionService } from "./retention/service.js";
import { LiveKitGateway } from "./calls/media.js";
import { createFcmTransport, PushService } from "./push/service.js";
import { ConfigError, loadConfig, requireAdminKey } from "./config/env.js";
import { PrismaDatabase } from "./db/database.js";

async function main() {
  let config;
  try {
    config = loadConfig();
    requireAdminKey(config);
  } catch (err) {
    if (err instanceof ConfigError) {
      // Messages name variables and rules only, never values.
      console.error(err.message);
      process.exit(1);
    }
    throw err;
  }

  const database = new PrismaDatabase(config, resolve(config.MIGRATIONS_DIR));
  const hasher = new PasswordHasher({ memoryCost: config.ARGON2_MEMORY_KIB, timeCost: config.ARGON2_TIME_COST });
  const auth = new AuthService(database.prisma, hasher);
  const admin = new AdminService(database.prisma, hasher, requireAdminKey(config));
  const connections = new ConnectionService(database.prisma);
  const media =
    config.LIVEKIT_PUBLIC_URL && config.LIVEKIT_API_URL && config.LIVEKIT_API_KEY && config.LIVEKIT_API_SECRET
      ? new LiveKitGateway(config.LIVEKIT_PUBLIC_URL, config.LIVEKIT_API_URL, config.LIVEKIT_API_KEY, config.LIVEKIT_API_SECRET)
      : null;
  const transport = config.FCM_CREDENTIALS_FILE ? await createFcmTransport(config.FCM_CREDENTIALS_FILE) : null;
  let logPush: (msg: string, extra?: Record<string, unknown>) => void = () => {};
  const push = new PushService(database.prisma, transport, (msg, extra) => logPush(msg, extra));
  const hub = new RealtimeHub();
  const calls = new CallService(database.prisma, media, config.CALL_RING_TIMEOUT_SECONDS, undefined, push, hub);
  const chat = new ChatService(database.prisma, undefined, push, hub);
  const app = buildApp({ config, database, auth, connections, calls, chat, media, push, realtime: { hub } });
  logPush = (msg, extra) => app.log.warn(extra ?? {}, msg);
  // The database is owned (and closed) by the public app; the admin app shares it.
  const adminApp = buildAdminApp({ config, prisma: database.prisma, database, auth, admin, media, pushEnabled: push.enabled, realtime: hub });

  const retention = new RetentionService(database.prisma);
  // Hourly housekeeping: spent QR tokens and records past their retention period (D-12).
  // Failures are logged and retried next hour.
  const housekeeping = setInterval(() => {
    connections.cleanup().catch((err: unknown) => app.log.warn({ err }, "qr token cleanup failed"));
    retention.purge().catch((err: unknown) => app.log.warn({ err }, "retention purge failed"));
  }, 60 * 60 * 1000);
  housekeeping.unref();
  // Ring timeouts and stuck calls: checked every 5 seconds.
  const callSweep = setInterval(() => {
    calls.sweep().catch((err: unknown) => app.log.warn({ err }, "call sweep failed"));
  }, 5_000);
  callSweep.unref();
  // Chat exits by lapsed presence lease and the 24 h backstop (D-02): checked every 15 seconds.
  const chatSweep = setInterval(() => {
    chat.sweep().catch((err: unknown) => app.log.warn({ err }, "chat sweep failed"));
  }, 15_000);
  chatSweep.unref();

  let shuttingDown = false;
  const shutdown = (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    clearInterval(housekeeping);
    clearInterval(callSweep);
    clearInterval(chatSweep);
    app.log.info({ signal }, "shutting down");
    const force = setTimeout(() => {
      app.log.error("shutdown timed out, forcing exit");
      process.exit(1);
    }, config.SHUTDOWN_TIMEOUT_MS);
    force.unref();
    adminApp
      .close()
      .then(() => app.close())
      .then(() => {
        app.log.info("shutdown complete");
        process.exit(0);
      })
      .catch((err: unknown) => {
        app.log.error({ err }, "error during shutdown");
        process.exit(1);
      });
  };
  process.once("SIGTERM", () => shutdown("SIGTERM"));
  process.once("SIGINT", () => shutdown("SIGINT"));
  process.on("unhandledRejection", (reason) => {
    app.log.fatal({ err: reason }, "unhandled promise rejection");
    shutdown("unhandledRejection");
  });

  await app.listen({ host: config.HOST, port: config.PORT });
  await adminApp.listen({ host: config.ADMIN_HOST, port: config.ADMIN_PORT });

  // Start even if the database is down, so liveness works and /readyz reports the problem.
  const readiness = await database.checkReadiness(config.READINESS_DB_TIMEOUT_MS);
  // Catch up at once on what happened while the server was down (docs/16 §5): chat leases that
  // lapsed are exits, messages past 24 h go, and calls left ringing or unjoined are closed.
  if (readiness.ready) {
    await chat.sweep().catch((err: unknown) => app.log.warn({ err }, "startup chat sweep failed"));
    await calls.sweep().catch((err: unknown) => app.log.warn({ err }, "startup call sweep failed"));
    await retention.purge().catch((err: unknown) => app.log.warn({ err }, "startup retention purge failed"));
  }
  if (!media) app.log.warn("LiveKit not configured: calling returns 503 MEDIA_UNAVAILABLE");
  if (!push.enabled) app.log.warn("FCM not configured: incoming calls ring only while the app is open");
  if (readiness.ready) app.log.info("database ready");
  else app.log.warn({ problems: readiness.problems }, "database not ready at startup");
}

main().catch((err: unknown) => {
  console.error("fatal startup error", err instanceof Error ? err.message : err);
  process.exit(1);
});
