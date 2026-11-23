import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import { AVATARS, NICKNAME_MAX, type ProfileService } from "../profile/service.js";

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

/** Avatars and nicknames (D-32, docs/14-api-contracts.md §profile). */
export async function profileRoutes(app: FastifyInstance, opts: { auth: AuthService; profile: ProfileService }) {
  const { profile } = opts;
  const user = requireUser(opts.auth);
  const MINUTE = 60_000;

  app.put<{ Body: { avatar: string | null } }>(
    "/me/avatar",
    {
      onRequest: user,
      config: { rateLimit: { max: 30, timeWindow: MINUTE } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["avatar"],
          properties: { avatar: { anyOf: [{ type: "null" }, { type: "string", enum: [...AVATARS] }] } },
        },
        response: { 200: { type: "object", additionalProperties: false, required: ["avatar"], properties: { avatar: { type: ["string", "null"] } } } },
      },
    },
    async (request) => profile.setAvatar(authOf(request).user.id, request.body.avatar),
  );

  app.put<{ Params: { id: string }; Body: { nickname: string | null } }>(
    "/connections/:id/nickname",
    {
      onRequest: user,
      config: { rateLimit: { max: 20, timeWindow: MINUTE } },
      schema: {
        params: { type: "object", required: ["id"], additionalProperties: false, properties: { id: { type: "string", pattern: UUID } } },
        body: {
          type: "object",
          additionalProperties: false,
          required: ["nickname"],
          properties: { nickname: { type: ["string", "null"], maxLength: NICKNAME_MAX + 20 } },
        },
        response: { 200: { type: "object", additionalProperties: false, required: ["nickname"], properties: { nickname: { type: ["string", "null"] } } } },
      },
    },
    async (request) => profile.setNickname(authOf(request).user.id, request.params.id, request.body.nickname),
  );
}
