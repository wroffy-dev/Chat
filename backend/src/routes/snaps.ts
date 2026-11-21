import type { Readable } from "node:stream";
import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import { appError } from "../errors.js";
import type { SnapKind, SnapService } from "../snaps/service.js";

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
const idParams = { type: "object", required: ["id"], additionalProperties: false, properties: { id: { type: "string", pattern: UUID } } } as const;
const TYPES: Record<SnapKind, string> = { photo: "image/jpeg", video: "video/mp4" };

/** Snaps (D-27, docs/14-api-contracts.md §5a). The media bytes are never logged. */
export async function snapRoutes(app: FastifyInstance, opts: { auth: AuthService; snaps: SnapService }) {
  const { snaps } = opts;
  const user = requireUser(opts.auth);
  const MINUTE = 60_000;

  // The body is streamed to disk, never buffered; SnapService enforces the size limits.
  app.addContentTypeParser(["image/jpeg", "video/mp4"], (_request, payload, done) => done(null, payload));

  app.post<{ Params: { id: string }; Querystring: { kind: SnapKind; once?: boolean } }>(
    "/connections/:id/snaps",
    {
      onRequest: user,
      config: { rateLimit: { max: 30, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        querystring: {
          type: "object",
          additionalProperties: false,
          required: ["kind"],
          // once=true: view once, no replay (owner, 2026-11-21).
          properties: { kind: { type: "string", enum: ["photo", "video"] }, once: { type: "boolean" } },
        },
        response: { 201: { type: "object", additionalProperties: false, required: ["id", "streak"], properties: { id: { type: "string" }, streak: { type: "integer" } } } },
      },
    },
    async (request, reply) => {
      const kind = request.query.kind;
      if (request.headers["content-type"] !== TYPES[kind]) throw appError(415, "UNSUPPORTED_MEDIA_TYPE");
      const sent = await snaps.send(authOf(request).user.id, request.params.id, kind, request.body as Readable, request.query.once === true);
      return reply.status(201).send(sent);
    },
  );

  app.get(
    "/snaps",
    {
      onRequest: user,
      config: { rateLimit: { max: 120, timeWindow: MINUTE } },
      schema: {
        response: {
          200: {
            type: "object",
            additionalProperties: false,
            required: ["items"],
            properties: {
              items: {
                type: "array",
                items: {
                  type: "object",
                  additionalProperties: false,
                  required: ["id", "connectionId", "from", "kind", "viewOnce", "createdAt"],
                  properties: {
                    id: { type: "string" },
                    connectionId: { type: "string" },
                    from: { type: "object", additionalProperties: false, required: ["id", "username"], properties: { id: { type: "string" }, username: { type: "string" } } },
                    kind: { type: "string" },
                    viewOnce: { type: "boolean" },
                    createdAt: { type: "string", format: "date-time" },
                  },
                },
              },
            },
          },
        },
      },
    },
    async (request) => ({ items: (await snaps.inbox(authOf(request).user.id)).map((s) => ({ ...s, createdAt: s.createdAt.toISOString() })) }),
  );

  // Opening a snap hands out its bytes once and deletes it on the server.
  app.post<{ Params: { id: string } }>(
    "/snaps/:id/open",
    {
      onRequest: user,
      bodyLimit: 1_024,
      config: { rateLimit: { max: 60, timeWindow: MINUTE } },
      schema: { params: idParams, body: { type: "object", additionalProperties: false, properties: {} } },
    },
    async (request, reply) => {
      const opened = await snaps.open(authOf(request).user.id, request.params.id);
      // The app shows a view-once snap a single time, with no replay.
      return reply
        .type(TYPES[opened.kind])
        .header("Cache-Control", "no-store")
        .header("X-Snap-View-Once", opened.viewOnce ? "true" : "false")
        .send(opened.bytes);
    },
  );
}
