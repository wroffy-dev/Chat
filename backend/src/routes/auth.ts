import type { FastifyInstance } from "fastify";
import { authOf, requestMeta, requireUser } from "../auth/plugin.js";
import type { AuthService, LoginResult } from "../auth/service.js";

const tokenResponse = {
  type: "object",
  additionalProperties: false,
  required: ["accessToken", "accessExpiresAt", "refreshToken", "user"],
  properties: {
    accessToken: { type: "string" },
    accessExpiresAt: { type: "string", format: "date-time" },
    refreshToken: { type: "string" },
    user: {
      type: "object",
      additionalProperties: false,
      required: ["id", "username", "mustChangePassword"],
      properties: { id: { type: "string" }, username: { type: "string" }, mustChangePassword: { type: "boolean" } },
    },
  },
} as const;

function toBody(r: LoginResult) {
  return { ...r, accessExpiresAt: r.accessExpiresAt.toISOString() };
}

const MINUTE = 60_000;

/** Routes under /api/v1 for app users (docs/14-api-contracts.md §2). */
export async function authRoutes(app: FastifyInstance, opts: { auth: AuthService }) {
  const { auth } = opts;

  app.post<{ Body: { username: string; password: string; device?: { model?: string; appVersionCode?: number } } }>(
    "/auth/login",
    {
      config: { rateLimit: { max: 10, timeWindow: MINUTE } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["username", "password"],
          properties: {
            username: { type: "string", minLength: 1, maxLength: 64 },
            password: { type: "string", minLength: 1, maxLength: 512 },
            device: {
              type: "object",
              additionalProperties: false,
              properties: {
                model: { type: "string", maxLength: 64 },
                appVersionCode: { type: "integer", minimum: 0, maximum: 2_147_483_647 },
              },
            },
          },
        },
        response: { 200: tokenResponse },
      },
    },
    async (request) => {
      const { username, password, device } = request.body;
      const result = await auth.login(username, password, {
        ...requestMeta(request),
        deviceModel: device?.model,
        appVersionCode: device?.appVersionCode,
      });
      return toBody(result);
    },
  );

  app.post<{ Body: { refreshToken: string } }>(
    "/auth/refresh",
    {
      config: { rateLimit: { max: 30, timeWindow: MINUTE } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["refreshToken"],
          properties: { refreshToken: { type: "string", minLength: 10, maxLength: 200 } },
        },
        response: { 200: tokenResponse },
      },
    },
    async (request) => toBody(await auth.refresh(request.body.refreshToken, requestMeta(request))),
  );

  app.post(
    "/auth/logout",
    {
      preHandler: requireUser(auth, { allowRestricted: true }),
      schema: { body: { type: "object", additionalProperties: false, properties: {} } },
    },
    async (request, reply) => {
      await auth.logout(authOf(request), requestMeta(request));
      return reply.status(204).send();
    },
  );

  app.post<{ Body: { currentPassword: string; newPassword: string } }>(
    "/auth/change-password",
    {
      preHandler: requireUser(auth, { allowRestricted: true }),
      config: { rateLimit: { max: 5, timeWindow: 15 * MINUTE } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["currentPassword", "newPassword"],
          properties: {
            currentPassword: { type: "string", minLength: 1, maxLength: 512 },
            newPassword: { type: "string", minLength: 1, maxLength: 512 },
          },
        },
        response: { 200: tokenResponse },
      },
    },
    async (request) => {
      const { currentPassword, newPassword } = request.body;
      return toBody(await auth.changePassword(authOf(request), currentPassword, newPassword, requestMeta(request)));
    },
  );

  app.get(
    "/me",
    {
      preHandler: requireUser(auth),
      schema: {
        response: {
          200: {
            type: "object",
            additionalProperties: false,
            required: ["id", "username", "mustChangePassword", "connection"],
            properties: {
              id: { type: "string" },
              username: { type: "string" },
              mustChangePassword: { type: "boolean" },
              // Pairing arrives with the QR-connection phase; always null until then.
              connection: { type: "null" },
            },
          },
        },
      },
    },
    async (request) => {
      const { user } = authOf(request);
      return { id: user.id, username: user.username, mustChangePassword: user.mustChangePassword, connection: null };
    },
  );
}
