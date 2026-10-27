import type { FastifyInstance } from "fastify";
import type { Database } from "../db/database.js";

const statusResponse = {
  type: "object",
  properties: { status: { type: "string" } },
  required: ["status"],
  additionalProperties: false,
} as const;

/**
 * Liveness and readiness (docs/14-api-contracts.md §8). Public responses carry no version,
 * dependency or error details; reasons for not being ready are logged server-side only.
 */
export async function healthRoutes(
  app: FastifyInstance,
  opts: { database: Database; readinessTimeoutMs: number },
) {
  app.get("/healthz", { schema: { response: { 200: statusResponse } } }, async () => ({ status: "ok" }));

  app.get(
    "/readyz",
    { schema: { response: { 200: statusResponse, 503: statusResponse } } },
    async (request, reply) => {
      const readiness = await opts.database.checkReadiness(opts.readinessTimeoutMs);
      if (readiness.ready) return { status: "ready" };

      request.log.warn(
        { problems: readiness.problems, pendingMigrations: readiness.pendingMigrations },
        "not ready",
      );
      return reply.status(503).send({ status: "not_ready" });
    },
  );
}
