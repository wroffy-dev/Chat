import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import type { CallService, CallView, JoinInfo } from "../calls/service.js";
import type { MediaGateway } from "../calls/media.js";

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
const idParams = { type: "object", required: ["id"], additionalProperties: false, properties: { id: { type: "string", pattern: UUID } } } as const;
const emptyBody = { type: "object", additionalProperties: false, properties: {} } as const;

const joinSchema = {
  type: "object",
  additionalProperties: false,
  required: ["callId", "livekitUrl", "livekitToken", "maxVideoQuality", "ringTimeoutSeconds"],
  properties: {
    callId: { type: "string" },
    livekitUrl: { type: "string" },
    livekitToken: { type: "string" },
    maxVideoQuality: { type: "string" },
    ringTimeoutSeconds: { type: "integer" },
  },
} as const;

const callSchema = {
  type: "object",
  additionalProperties: false,
  required: ["id", "kind", "status", "role", "peer", "createdAt", "acceptedAt", "endedAt", "endReason"],
  properties: {
    id: { type: "string" },
    kind: { type: "string" },
    status: { type: "string" },
    role: { type: "string" },
    peer: { type: "object", additionalProperties: false, required: ["id", "username"], properties: { id: { type: "string" }, username: { type: "string" } } },
    createdAt: { type: "string", format: "date-time" },
    acceptedAt: { type: ["string", "null"], format: "date-time" },
    endedAt: { type: ["string", "null"], format: "date-time" },
    endReason: { type: ["string", "null"] },
  },
} as const;

const viewJson = (c: CallView) => ({
  ...c,
  createdAt: c.createdAt.toISOString(),
  acceptedAt: c.acceptedAt?.toISOString() ?? null,
  endedAt: c.endedAt?.toISOString() ?? null,
});

/**
 * Diagnostics summary: numbers and short labels only — never media, IP addresses or location
 * (docs/08-calling.md §11). Bounded so the endpoint cannot be used as storage.
 */
const diagnosticsBody = {
  type: "object",
  additionalProperties: false,
  required: ["summary"],
  properties: {
    summary: {
      type: "object",
      maxProperties: 40,
      additionalProperties: {
        anyOf: [
          { type: "number" },
          { type: "boolean" },
          { type: "string", maxLength: 64 },
          { type: "null" },
        ],
      },
    },
  },
} as const;

/** Calling routes (docs/14-api-contracts.md §6). */
export async function callRoutes(app: FastifyInstance, opts: { auth: AuthService; calls: CallService }) {
  const { auth, calls } = opts;
  const user = requireUser(auth);
  const MINUTE = 60_000;

  app.post<{ Body: { connectionId: string; kind: "video" | "voice" } }>(
    "/calls",
    {
      onRequest: user,
      config: { rateLimit: { max: 10, timeWindow: MINUTE } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["connectionId", "kind"],
          properties: { connectionId: { type: "string", pattern: UUID }, kind: { type: "string", enum: ["video", "voice"] } },
        },
        response: { 201: joinSchema },
      },
    },
    async (request, reply) => reply.status(201).send(await calls.start(authOf(request).user.id, request.body.connectionId, request.body.kind)),
  );

  app.get(
    "/calls/current",
    { onRequest: user, schema: { response: { 200: { type: "object", additionalProperties: false, required: ["call"], properties: { call: { anyOf: [{ type: "null" }, callSchema] } } } } } },
    async (request) => {
      const c = await calls.current(authOf(request).user.id);
      return { call: c ? viewJson(c) : null };
    },
  );

  app.get<{ Params: { id: string } }>(
    "/calls/:id",
    { onRequest: user, schema: { params: idParams, response: { 200: callSchema } } },
    async (request) => viewJson(await calls.get(authOf(request).user.id, request.params.id)),
  );

  const joinRoute = (path: string, fn: (userId: string, id: string) => Promise<JoinInfo>) =>
    app.post<{ Params: { id: string } }>(
      path,
      { onRequest: user, config: { rateLimit: { max: 20, timeWindow: MINUTE } }, schema: { params: idParams, body: emptyBody, response: { 200: joinSchema } } },
      async (request) => fn(authOf(request).user.id, request.params.id),
    );
  joinRoute("/calls/:id/accept", (u, id) => calls.accept(u, id));
  joinRoute("/calls/:id/token", (u, id) => calls.token(u, id));

  for (const action of ["decline", "cancel"] as const) {
    app.post<{ Params: { id: string } }>(
      `/calls/:id/${action}`,
      { onRequest: user, schema: { params: idParams, body: emptyBody } },
      async (request, reply) => {
        const { id } = authOf(request).user;
        if (action === "decline") await calls.decline(id, request.params.id);
        else await calls.cancel(id, request.params.id);
        return reply.status(204).send();
      },
    );
  }

  app.post<{ Params: { id: string }; Body: { reason: "hangup" | "network_lost" | "error" } }>(
    "/calls/:id/end",
    {
      onRequest: user,
      schema: {
        params: idParams,
        body: { type: "object", additionalProperties: false, required: ["reason"], properties: { reason: { type: "string", enum: ["hangup", "network_lost", "error"] } } },
      },
    },
    async (request, reply) => {
      await calls.end(authOf(request).user.id, request.params.id, request.body.reason);
      return reply.status(204).send();
    },
  );

  app.post<{ Params: { id: string }; Body: { summary: Record<string, number | boolean | string | null> } }>(
    "/calls/:id/diagnostics",
    { onRequest: user, config: { rateLimit: { max: 10, timeWindow: MINUTE } }, schema: { params: idParams, body: diagnosticsBody } },
    async (request, reply) => {
      await calls.saveDiagnostics(authOf(request).user.id, request.params.id, request.body.summary);
      return reply.status(204).send();
    },
  );
}

/**
 * LiveKit → backend webhooks. Authenticated by LiveKit's signed Authorization header, which covers
 * a hash of the raw body, so the body is kept as text. Must not be routed by the public proxy
 * (docs/16-deployment-and-operations.md §5); the signature check applies regardless.
 */
export async function livekitWebhookRoutes(app: FastifyInstance, opts: { calls: CallService; media: MediaGateway }) {
  app.addContentTypeParser(["application/webhook+json", "application/json"], { parseAs: "string", bodyLimit: 65_536 }, (_req, body, done) => done(null, body));

  app.post("/internal/livekit/webhook", async (request, reply) => {
    let event;
    try {
      event = await opts.media.verifyWebhook(String(request.body ?? ""), request.headers.authorization);
    } catch {
      request.log.warn("rejected livekit webhook with invalid signature");
      return reply.status(401).send({ error: { code: "UNAUTHENTICATED", message: "Invalid webhook signature.", requestId: request.id } });
    }
    await opts.calls.handleWebhook(event);
    return reply.status(200).send({ ok: true });
  });
}
