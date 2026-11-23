import type { PrismaClient } from "../generated/prisma/client.js";
import { appError } from "../errors.js";

/** Longest note title and body, in characters (also enforced by CHECK constraints in SQL). */
export const NOTE_TITLE_MAX = 200;
export const NOTE_BODY_MAX = 20_000;

/** At most this many notes per user, so one account cannot fill the database. */
export const NOTES_PER_USER_MAX = 2_000;

export interface NoteView {
  id: string;
  title: string;
  body: string;
  createdAt: string;
  updatedAt: string;
}

type NoteRow = { id: string; title: string; body: string; createdAt: Date; updatedAt: Date };

const view = (n: NoteRow): NoteView => ({
  id: n.id,
  title: n.title,
  body: n.body,
  createdAt: n.createdAt.toISOString(),
  updatedAt: n.updatedAt.toISOString(),
});

/**
 * A user's own notes (D-31, owner 2026-11-23: the app opens on notes, "Just Notes"). Every query is scoped to
 * the signed-in user: another user's note is reported as not found, never as forbidden, so ids reveal nothing.
 */
export class NoteService {
  constructor(private readonly prisma: PrismaClient) {}

  async list(userId: string): Promise<NoteView[]> {
    const rows = await this.prisma.note.findMany({ where: { userId }, orderBy: { updatedAt: "desc" }, take: NOTES_PER_USER_MAX });
    return rows.map(view);
  }

  async create(userId: string, input: { title: string; body: string }): Promise<NoteView> {
    const count = await this.prisma.note.count({ where: { userId } });
    if (count >= NOTES_PER_USER_MAX) throw appError(409, "CONFLICT");
    return view(await this.prisma.note.create({ data: { userId, title: input.title, body: input.body } }));
  }

  async update(userId: string, id: string, patch: { title?: string; body?: string }): Promise<NoteView> {
    const changed = await this.prisma.note.updateMany({ where: { id, userId }, data: patch });
    if (changed.count === 0) throw appError(404, "NOT_FOUND");
    const row = await this.prisma.note.findFirst({ where: { id, userId } });
    if (!row) throw appError(404, "NOT_FOUND");
    return view(row);
  }

  async remove(userId: string, id: string): Promise<void> {
    const deleted = await this.prisma.note.deleteMany({ where: { id, userId } });
    if (deleted.count === 0) throw appError(404, "NOT_FOUND");
  }
}
