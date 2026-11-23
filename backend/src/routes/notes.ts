import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import { NOTE_BODY_MAX, NOTE_TITLE_MAX, type NoteService } from "../notes/service.js";

const UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";
const idParams = { type: "object", required: ["id"], additionalProperties: false, properties: { id: { type: "string", pattern: UUID } } } as const;
const noteSchema = {
  type: "object",
  additionalProperties: false,
  required: ["id", "title", "body", "createdAt", "updatedAt"],
  properties: {
    id: { type: "string" },
    title: { type: "string" },
    body: { type: "string" },
    createdAt: { type: "string" },
    updatedAt: { type: "string" },
  },
} as const;
const title = { type: "string", maxLength: NOTE_TITLE_MAX } as const;
const body = { type: "string", maxLength: NOTE_BODY_MAX } as const;

/** The signed-in user's own notes (D-31, docs/14-api-contracts.md §notes). Note text is never logged. */
export async function noteRoutes(app: FastifyInstance, opts: { auth: AuthService; notes: NoteService }) {
  const { notes } = opts;
  const user = requireUser(opts.auth);
  const MINUTE = 60_000;

  app.get(
    "/notes",
    {
      onRequest: user,
      schema: { response: { 200: { type: "object", additionalProperties: false, required: ["items"], properties: { items: { type: "array", items: noteSchema } } } } },
    },
    async (request) => ({ items: await notes.list(authOf(request).user.id) }),
  );

  app.post<{ Body: { title?: string; body?: string } }>(
    "/notes",
    {
      onRequest: user,
      config: { rateLimit: { max: 60, timeWindow: MINUTE } },
      schema: {
        body: { type: "object", additionalProperties: false, properties: { title, body } },
        response: { 201: noteSchema },
      },
    },
    async (request, reply) => {
      const created = await notes.create(authOf(request).user.id, { title: request.body.title ?? "", body: request.body.body ?? "" });
      return reply.code(201).send(created);
    },
  );

  app.patch<{ Params: { id: string }; Body: { title?: string; body?: string } }>(
    "/notes/:id",
    {
      onRequest: user,
      // Edits are saved as the user types (debounced on the phone), so the limit is generous.
      config: { rateLimit: { max: 240, timeWindow: MINUTE } },
      schema: {
        params: idParams,
        body: { type: "object", additionalProperties: false, minProperties: 1, properties: { title, body } },
        response: { 200: noteSchema },
      },
    },
    async (request) => notes.update(authOf(request).user.id, request.params.id, request.body),
  );

  app.delete<{ Params: { id: string } }>(
    "/notes/:id",
    { onRequest: user, config: { rateLimit: { max: 60, timeWindow: MINUTE } }, schema: { params: idParams } },
    async (request, reply) => {
      await notes.remove(authOf(request).user.id, request.params.id);
      return reply.code(204).send();
    },
  );
}
