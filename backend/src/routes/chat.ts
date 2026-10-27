import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import type { ChatService, MessageView } from "../chat/service.js";

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
const idParams = { type: "object", required: ["id"], additionalProperties: false, properties: { id: { type: "string", pattern: UUID } } } as const;
const emptyBody = { type: "object", additionalProperties: false, properties: {} } as const;
const SEQ = { type: "integer", minimum: 0, maximum: Number.MAX_SAFE_INTEGER } as const;

const messageSchema = {
  type: "object",
  additionalProperties: false,
  required: ["id", "seq", "senderId", "text", "createdAt"],
  properties: {
    id: { type: "string" },
    seq: { type: "integer" },
    senderId: { type: "string" },
    text: { type: "string" },
    createdAt: { type: "string", format: "date-time" },
  },
} as const;

const sentSchema = {
  type: "object",
  additionalProperties: false,
  required: ["id", "seq", "epoch", "createdAt"],
  properties: { id: { type: "string" }, seq: { type: "integer" }, epoch: { type: "integer" }, createdAt: { type: "string", format: "date-time" } },
} as const;

const messageJson = (m: MessageView) => ({ ...m, createdAt: m.createdAt.toISOString() });

/**
 * Chat routes (docs/14-api-contracts.md §5). Every route requires the caller to be a participant
 * of the conversation (else 404). Request bodies of these routes are never logged.
 */
export async function chatRoutes(app: FastifyInstance, opts: { auth: AuthService; chat: ChatService }) {
  const { auth, chat } = opts;
  const user = requireUser(auth);
  const MINUTE = 60_000;

  app.post<{ Params: { id: string } }>(
    "/conversations/:id/enter",
    {
      onRequest: user,
      config: { rateLimit: { max: 30, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        body: emptyBody,
        response: {
          200: {
            type: "object",
            additionalProperties: false,
            required: ["leaseId", "leaseTtlSeconds", "heartbeatSeconds", "epoch"],
            properties: { leaseId: { type: "string" }, leaseTtlSeconds: { type: "integer" }, heartbeatSeconds: { type: "integer" }, epoch: { type: "integer" } },
          },
        },
      },
    },
    async (request) => chat.enter(authOf(request).user.id, request.params.id),
  );

  app.post<{ Params: { id: string }; Body: { leaseId: string } }>(
    "/conversations/:id/heartbeat",
    {
      onRequest: user,
      config: { rateLimit: { max: 20, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        body: { type: "object", additionalProperties: false, required: ["leaseId"], properties: { leaseId: { type: "string", pattern: UUID } } },
      },
    },
    async (request, reply) => {
      await chat.heartbeat(authOf(request).user.id, request.params.id, request.body.leaseId);
      return reply.status(204).send();
    },
  );

  app.post<{ Params: { id: string }; Body: { leaveId: string } }>(
    "/conversations/:id/leave",
    {
      onRequest: user,
      config: { rateLimit: { max: 30, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        body: { type: "object", additionalProperties: false, required: ["leaveId"], properties: { leaveId: { type: "string", pattern: UUID } } },
        response: { 200: { type: "object", additionalProperties: false, required: ["epoch"], properties: { epoch: { type: "integer" } } } },
      },
    },
    async (request) => chat.leave(authOf(request).user.id, request.params.id, request.body.leaveId),
  );

  app.get<{ Params: { id: string }; Querystring: { afterSeq?: number; epoch?: number; limit?: number } }>(
    "/conversations/:id/messages",
    {
      onRequest: user,
      config: { rateLimit: { max: 120, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        querystring: {
          type: "object",
          additionalProperties: false,
          properties: { afterSeq: SEQ, epoch: SEQ, limit: { type: "integer", minimum: 1, maximum: 200 } },
        },
        response: {
          200: {
            type: "object",
            additionalProperties: false,
            required: ["epoch", "cleared", "items", "hasMore"],
            properties: { epoch: { type: "integer" }, cleared: { type: "boolean" }, items: { type: "array", items: messageSchema }, hasMore: { type: "boolean" } },
          },
        },
      },
    },
    async (request) => {
      const { afterSeq, epoch, limit } = request.query;
      const h = await chat.history(authOf(request).user.id, request.params.id, {
        afterSeq: afterSeq ?? 0,
        ...(epoch !== undefined ? { epoch } : {}),
        ...(limit !== undefined ? { limit } : {}),
      });
      return { ...h, items: h.items.map(messageJson) };
    },
  );

  app.post<{ Params: { id: string }; Body: { clientMessageId: string; epoch: number; text: string } }>(
    "/conversations/:id/messages",
    {
      onRequest: user,
      bodyLimit: 16_384,
      config: { rateLimit: { max: 60, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        body: {
          type: "object",
          additionalProperties: false,
          required: ["clientMessageId", "epoch", "text"],
          properties: { clientMessageId: { type: "string", pattern: UUID }, epoch: SEQ, text: { type: "string", minLength: 1 } },
        },
        response: { 200: sentSchema, 201: sentSchema },
      },
    },
    async (request, reply) => {
      const { created, ...sent } = await chat.send(authOf(request).user.id, request.params.id, request.body);
      return reply.status(created ? 201 : 200).send({ ...sent, createdAt: sent.createdAt.toISOString() });
    },
  );

  app.post<{ Params: { id: string }; Body: { seq: number } }>(
    "/conversations/:id/read",
    {
      onRequest: user,
      config: { rateLimit: { max: 120, timeWindow: MINUTE } },
      schema: { params: idParams, body: { type: "object", additionalProperties: false, required: ["seq"], properties: { seq: SEQ } } },
    },
    async (request, reply) => {
      await chat.markRead(authOf(request).user.id, request.params.id, request.body.seq);
      return reply.status(204).send();
    },
  );
}
