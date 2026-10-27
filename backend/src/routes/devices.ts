import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import type { PushService } from "../push/service.js";

/** Push-token registration (docs/14-api-contracts.md §3). */
export async function deviceRoutes(app: FastifyInstance, opts: { auth: AuthService; push: PushService }) {
  const user = requireUser(opts.auth, { allowRestricted: true });

  app.put<{ Body: { fcmToken: string } }>(
    "/devices/push-token",
    {
      onRequest: user,
      config: { rateLimit: { max: 10, timeWindow: 60_000 } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["fcmToken"],
          properties: { fcmToken: { type: "string", minLength: 20, maxLength: 4096, pattern: "^[A-Za-z0-9_:\\-]+$" } },
        },
      },
    },
    async (request, reply) => {
      await opts.push.registerToken(authOf(request).session.id, request.body.fcmToken);
      return reply.status(204).send();
    },
  );

  app.delete("/devices/push-token", { onRequest: user }, async (request, reply) => {
    await opts.push.clearToken(authOf(request).session.id);
    return reply.status(204).send();
  });
}
