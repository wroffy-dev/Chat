import type { PrismaClient } from "../generated/prisma/client.js";
import { appError } from "../errors.js";

/** Longest note title and body, in characters (also enforced by CHECK constraints in SQL). */
export const NOTE_TITLE_MAX = 200;
export const NOTE_BODY_MAX = 20_000;
/** Longest added line, in characters (CHECK in SQL). */
export const NOTE_LINE_MAX = 2_000;

/** At most this many notes per user, so one account cannot fill the database. */
export const NOTES_PER_USER_MAX = 2_000;
/** At most this many added lines under one note. */
export const LINES_PER_NOTE_MAX = 500;

export interface NoteLineView {
  id: string;
  body: string;
  authorUsername: string;
  /** The signed-in user's own name for the author (D-32), or null. */
  authorNickname: string | null;
  /** Added by the signed-in user. */
  mine: boolean;
  createdAt: string;
}

export interface NoteView {
  id: string;
  title: string;
  body: string;
  /** Written by the signed-in user; otherwise a connected person's note, which they can only add lines to. */
  mine: boolean;
  authorUsername: string;
  /** The signed-in user's own name for the writer (D-32), or null. */
  authorNickname: string | null;
  /** The writer's built-in avatar (D-32), or null. */
  authorAvatar: string | null;
  lines: NoteLineView[];
  createdAt: string;
  updatedAt: string;
}

const include = {
  user: { select: { username: true, avatar: true } },
  lines: { orderBy: { createdAt: "asc" }, take: LINES_PER_NOTE_MAX, include: { user: { select: { username: true } } } },
} as const;

type NoteRow = {
  id: string;
  userId: string;
  title: string;
  body: string;
  createdAt: Date;
  updatedAt: Date;
  user: { username: string; avatar: string | null };
  lines: { id: string; userId: string; body: string; createdAt: Date; user: { username: string } }[];
};

/** Who the user can see, and the user's own names for them (D-32). */
type People = { writers: string[]; nicknames: Map<string, string> };

const view = (me: string, n: NoteRow, nicknames: Map<string, string> = new Map()): NoteView => ({
  id: n.id,
  title: n.title,
  body: n.body,
  mine: n.userId === me,
  authorUsername: n.user.username,
  authorNickname: nicknames.get(n.userId) ?? null,
  authorAvatar: n.user.avatar,
  lines: n.lines.map((l) => ({
    id: l.id,
    body: l.body,
    authorUsername: l.user.username,
    authorNickname: nicknames.get(l.userId) ?? null,
    mine: l.userId === me,
    createdAt: l.createdAt.toISOString(),
  })),
  createdAt: n.createdAt.toISOString(),
  updatedAt: n.updatedAt.toISOString(),
});

/**
 * Notes (D-31, owner 2026-11-23: the app opens on notes, "Just Notes"). A user sees their own notes and the notes of
 * the people they are connected with (owner, 2026-11-23: shared, others can add lines). Only the writer can change
 * a note's title and text or delete it; the people they are connected with can add lines under it. A note nobody
 * may see is reported as not found, never as forbidden, so ids reveal nothing.
 */
export class NoteService {
  constructor(private readonly prisma: PrismaClient) {}

  /** The user and everyone they are connected with (whose notes they can see), with the user's nicknames for them. */
  private async people(userId: string): Promise<People> {
    const connections = await this.prisma.connection.findMany({
      where: { OR: [{ userAId: userId }, { userBId: userId }] },
      select: { userAId: true, userBId: true, nicknames: { where: { userId }, select: { nickname: true } } },
    });
    const nicknames = new Map<string, string>();
    const writers = [userId];
    for (const c of connections) {
      const peer = c.userAId === userId ? c.userBId : c.userAId;
      writers.push(peer);
      const nickname = c.nicknames[0]?.nickname;
      if (nickname) nicknames.set(peer, nickname);
    }
    return { writers, nicknames };
  }

  private async visibleNote(userId: string, id: string, people?: People): Promise<{ row: NoteRow; people: People }> {
    const p = people ?? (await this.people(userId));
    const row = await this.prisma.note.findFirst({ where: { id, userId: { in: p.writers } }, include });
    if (!row) throw appError(404, "NOT_FOUND");
    return { row, people: p };
  }

  private async noteView(userId: string, id: string): Promise<NoteView> {
    const { row, people } = await this.visibleNote(userId, id);
    return view(userId, row, people.nicknames);
  }

  async list(userId: string): Promise<NoteView[]> {
    const { writers, nicknames } = await this.people(userId);
    const rows = await this.prisma.note.findMany({
      where: { userId: { in: writers } },
      orderBy: { updatedAt: "desc" },
      take: NOTES_PER_USER_MAX * writers.length,
      include,
    });
    return rows.map((n) => view(userId, n, nicknames));
  }

  async create(userId: string, input: { title: string; body: string }): Promise<NoteView> {
    const count = await this.prisma.note.count({ where: { userId } });
    if (count >= NOTES_PER_USER_MAX) throw appError(409, "CONFLICT");
    return view(userId, await this.prisma.note.create({ data: { userId, title: input.title, body: input.body }, include }));
  }

  async update(userId: string, id: string, patch: { title?: string; body?: string }): Promise<NoteView> {
    const changed = await this.prisma.note.updateMany({ where: { id, userId }, data: patch });
    if (changed.count === 0) throw appError(404, "NOT_FOUND");
    return this.noteView(userId, id);
  }

  async remove(userId: string, id: string): Promise<void> {
    const deleted = await this.prisma.note.deleteMany({ where: { id, userId } });
    if (deleted.count === 0) throw appError(404, "NOT_FOUND");
  }

  /** Adds a line under a note the user can see (their own or a connected person's); the note moves to the top. */
  async addLine(userId: string, noteId: string, body: string): Promise<NoteView> {
    const { row: note } = await this.visibleNote(userId, noteId);
    const count = await this.prisma.noteLine.count({ where: { noteId } });
    if (count >= LINES_PER_NOTE_MAX) throw appError(409, "CONFLICT");
    await this.prisma.$transaction([
      this.prisma.noteLine.create({ data: { noteId, userId, body } }),
      this.prisma.note.update({ where: { id: note.id }, data: { updatedAt: new Date() } }),
    ]);
    return this.noteView(userId, noteId);
  }

  /** Removes a line: the person who added it, or the note's writer, may do so. */
  async removeLine(userId: string, noteId: string, lineId: string): Promise<void> {
    const { row: note } = await this.visibleNote(userId, noteId);
    const deleted = await this.prisma.noteLine.deleteMany({
      where: { id: lineId, noteId, ...(note.userId === userId ? {} : { userId }) },
    });
    if (deleted.count === 0) throw appError(404, "NOT_FOUND");
  }
}
