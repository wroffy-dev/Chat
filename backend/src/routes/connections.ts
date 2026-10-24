import type { FastifyInstance } from "fastify";
import { authOf, requestMeta, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import type { ConnectionService, RequestView } from "../connections/service.js";

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
const idParams = { type: "object", required: ["id"], additionalProperties: false, properties: { id: { type: "string", pattern: UUID } } } as const;
const emptyBody = { type: "object", additionalProperties: false, properties: {} } as const;

const requestSchema = {
  type: "object",
  additionalProperties: false,
  required: ["id", "direction", "peer", "status", "createdAt", "expiresAt"],
  properties: {
    id: { type: "string" },
    direction: { type: "string", enum: ["incoming", "outgoing"] },
    peer: { type: "object", additionalProperties: false, required: ["username"], properties: { username: { type: "string" } } },
    status: { type: "string" },
    createdAt: { type: "string", format: "date-time" },
    expiresAt: { type: "string", format: "date-time" },
  },
} as const;

export const connectionSchema = {
  type: "object",
  additionalProperties: false,
  required: ["id", "peer", "createdAt"],
  properties: {
    id: { type: "string" },
    peer: {
      type: "object",
      additionalProperties: false,
      required: ["id", "username"],
      properties: { id: { type: "string" }, username: { type: "string" } },
    },
    createdAt: { type: "string", format: "date-time" },
  },
} as const;

const toJson = (r: RequestView) => ({ ...r, createdAt: r.createdAt.toISOString(), expiresAt: r.expiresAt.toISOString() });

/** QR pairing (docs/14-api-contracts.md §4). No delete-connection endpoint exists in v1. */
export async function connectionRoutes(app: FastifyInstance, opts: { auth: AuthService; connections: ConnectionService }) {
  const { auth, connections } = opts;
  const user = requireUser(auth);

  app.post(
    "/connection-qr",
    {
      preHandler: user,
      schema: {
        body: emptyBody,
        response: {
          201: {
            type: "object",
            additionalProperties: false,
            required: ["token", "qrPayload", "expiresAt"],
            properties: { token: { type: "string" }, qrPayload: { type: "string" }, expiresAt: { type: "string", format: "date-time" } },
          },
        },
      },
    },
    async (request, reply) => {
      const qr = await connections.generateQr(authOf(request).user.id);
      return reply.status(201).send({ token: qr.token, qrPayload: qr.payload, expiresAt: qr.expiresAt.toISOString() });
    },
  );

  app.post<{ Body: { token: string } }>(
    "/connection-requests",
    {
      preHandler: user,
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["token"],
          properties: { token: { type: "string", pattern: "^vcq_[A-Za-z0-9_-]{43}$" } },
        },
        response: { 201: requestSchema },
      },
    },
    async (request, reply) => {
      const created = await connections.redeem(authOf(request).user.id, request.body.token, requestMeta(request).ip);
      return reply.status(201).send(toJson(created));
    },
  );

  app.get<{ Querystring: { direction?: "incoming" | "outgoing"; status?: "pending" | "accepted" | "rejected" | "cancelled" | "expired" } }>(
    "/connection-requests",
    {
      preHandler: user,
      schema: {
        querystring: {
          type: "object",
          additionalProperties: false,
          properties: {
            direction: { type: "string", enum: ["incoming", "outgoing"] },
            status: { type: "string", enum: ["pending", "accepted", "rejected", "cancelled", "expired"] },
          },
        },
        response: { 200: { type: "object", additionalProperties: false, required: ["items"], properties: { items: { type: "array", items: requestSchema } } } },
      },
    },
    async (request) => {
      const items = await connections.listRequests(authOf(request).user.id, request.query);
      return { items: items.map(toJson) };
    },
  );

  app.post<{ Params: { id: string } }>(
    "/connection-requests/:id/accept",
    { preHandler: user, schema: { params: idParams, body: emptyBody, response: { 200: connectionSchema } } },
    async (request) => {
      const c = await connections.accept(authOf(request).user.id, request.params.id, requestMeta(request).ip);
      return { ...c, createdAt: c.createdAt.toISOString() };
    },
  );

  for (const action of ["reject", "cancel"] as const) {
    app.post<{ Params: { id: string } }>(
      `/connection-requests/:id/${action}`,
      { preHandler: user, schema: { params: idParams, body: emptyBody } },
      async (request, reply) => {
        const { id } = authOf(request).user;
        if (action === "reject") await connections.reject(id, request.params.id, requestMeta(request).ip);
        else await connections.cancel(id, request.params.id, requestMeta(request).ip);
        return reply.status(204).send();
      },
    );
  }

  app.get(
    "/connections",
    {
      preHandler: user,
      schema: { response: { 200: { type: "object", additionalProperties: false, required: ["items"], properties: { items: { type: "array", items: connectionSchema } } } } },
    },
    async (request) => {
      const c = await connections.connectionFor(authOf(request).user.id);
      return { items: c ? [{ ...c, createdAt: c.createdAt.toISOString() }] : [] };
    },
  );
}
