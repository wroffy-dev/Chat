import { resolve } from "node:path";
import { buildAdminApp } from "./admin/app.js";
import { AdminService } from "./admin/service.js";
import { buildApp } from "./app.js";
import { PasswordHasher } from "./auth/passwords.js";
import { AuthService } from "./auth/service.js";
import { ConnectionService } from "./connections/service.js";
import { ConfigError, loadConfig } from "./config/env.js";
import { PrismaDatabase } from "./db/database.js";

async function main() {
  let config;
  try {
    config = loadConfig();
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
  const admin = new AdminService(database.prisma, hasher, config.ADMIN_TOTP_ENC_KEY);
  const connections = new ConnectionService(database.prisma);
  const app = buildApp({ config, database, auth, connections });
  // The database is owned (and closed) by the public app; the admin app shares it.
  const adminApp = buildAdminApp({ config, prisma: database.prisma, database, auth, admin });

  // Hourly housekeeping of spent QR tokens; failures are logged and retried next hour.
  const housekeeping = setInterval(() => {
    connections.cleanup().catch((err: unknown) => app.log.warn({ err }, "qr token cleanup failed"));
  }, 60 * 60 * 1000);
  housekeeping.unref();

  let shuttingDown = false;
  const shutdown = (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    clearInterval(housekeeping);
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
  if (readiness.ready) app.log.info("database ready");
  else app.log.warn({ problems: readiness.problems }, "database not ready at startup");
}

main().catch((err: unknown) => {
  console.error("fatal startup error", err instanceof Error ? err.message : err);
  process.exit(1);
});
