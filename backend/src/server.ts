import { resolve } from "node:path";
import { buildApp } from "./app.js";
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
  const app = buildApp({ config, database });

  let shuttingDown = false;
  const shutdown = (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    app.log.info({ signal }, "shutting down");
    const force = setTimeout(() => {
      app.log.error("shutdown timed out, forcing exit");
      process.exit(1);
    }, config.SHUTDOWN_TIMEOUT_MS);
    force.unref();
    app
      .close()
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

  // Start even if the database is down, so liveness works and /readyz reports the problem.
  const readiness = await database.checkReadiness(config.READINESS_DB_TIMEOUT_MS);
  if (readiness.ready) app.log.info("database ready");
  else app.log.warn({ problems: readiness.problems }, "database not ready at startup");
}

main().catch((err: unknown) => {
  console.error("fatal startup error", err instanceof Error ? err.message : err);
  process.exit(1);
});
