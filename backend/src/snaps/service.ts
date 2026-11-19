import { randomUUID } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, readFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { Transform, type Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import type { PrismaClient } from "../generated/prisma/client.js";
import { AppError, appError } from "../errors.js";
import type { PushService } from "../push/service.js";
import type { EventPublisher } from "../realtime/events.js";

/**
 * Snaps (owner decision D-27, 2026-11-19): a photo or a video of at most 7 s, taken with the camera and sent to
 * one paired person. The file waits on the server until the recipient opens it, is then deleted at once
 * (the app keeps it in memory for one replay), and is deleted after 24 h if never opened. The database holds
 * only the metadata; the bytes are never logged or backed up.
 */
export type SnapKind = "photo" | "video";

export const SNAP_POLICY = {
  maxPhotoBytes: 6 * 1024 * 1024,
  /** A 7 s video from the phone's camera at a modest bit rate (the app records at most 7 s). */
  maxVideoBytes: 20 * 1024 * 1024,
  /** Unopened snaps are deleted after this long (as messages are, D-02). */
  lifetimeMs: 24 * 60 * 60 * 1000,
  sendsPerMinute: 20,
} as const;

/** Streak days follow India time (both users are in India), so a day ends at local midnight. */
const DAY_OFFSET_MS = (5 * 60 + 30) * 60 * 1000;
export function streakDay(at: Date): string {
  return new Date(at.getTime() + DAY_OFFSET_MS).toISOString().slice(0, 10);
}
function previousDay(day: string): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) - 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
}

export interface SnapView {
  id: string;
  connectionId: string;
  from: { id: string; username: string };
  kind: SnapKind;
  createdAt: Date;
}

export class SnapService {
  private readonly sends = new Map<string, { windowStart: number; count: number }>();

  constructor(
    private readonly prisma: PrismaClient,
    /** Directory for the media files; without it snaps are unavailable (503). */
    private readonly dir: string | null,
    private readonly now: () => Date = () => new Date(),
    private readonly push: PushService | null = null,
    private readonly events: EventPublisher | null = null,
  ) {}

  private requireDir(): string {
    if (!this.dir) throw appError(503, "SNAPS_UNAVAILABLE");
    return this.dir;
  }

  private file(id: string) {
    return join(this.requireDir(), `${id}.snap`);
  }

  /** Stores a snap from [userId] to the other person of the connection and updates their streak. */
  async send(userId: string, connectionId: string, kind: SnapKind, body: Readable): Promise<{ id: string; streak: number }> {
    const dir = this.requireDir();
    const connection = await this.prisma.connection.findUnique({ where: { id: connectionId } });
    if (!connection || (connection.userAId !== userId && connection.userBId !== userId)) {
      body.resume();
      throw appError(404, "NOT_FOUND");
    }
    const now = this.now();
    this.limit(userId, now);
    const recipientUserId = connection.userAId === userId ? connection.userBId : connection.userAId;
    const id = randomUUID();
    const max = kind === "photo" ? SNAP_POLICY.maxPhotoBytes : SNAP_POLICY.maxVideoBytes;
    let size = 0;
    let head = Buffer.alloc(0);
    // Past the limit the rest of the body is read and dropped (not written), so the client always gets a
    // clean 413 instead of a connection cut in the middle of its upload.
    const counter = new Transform({
      transform(chunk: Buffer, _enc, cb) {
        size += chunk.length;
        if (size > max) return cb();
        if (head.length < 12) head = Buffer.concat([head, chunk.subarray(0, 12 - head.length)]);
        cb(null, chunk);
      },
    });
    const path = join(dir, `${id}.snap`);
    try {
      await mkdir(dir, { recursive: true });
      await pipeline(body, counter, createWriteStream(path, { mode: 0o600 }));
      if (size > max) throw new AppError(413, "PAYLOAD_TOO_LARGE", "The snap is too large.");
      // A JPEG starts FF D8 FF; an MP4 has "ftyp" at byte 4.
      const valid = kind === "photo"
        ? head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff
        : head.subarray(4, 8).toString("latin1") === "ftyp";
      if (size < 256 || !valid) throw new AppError(400, "VALIDATION_FAILED", "That is not a camera photo or video.");
      await this.prisma.snap.create({ data: { id, connectionId, senderUserId: userId, recipientUserId, kind, sizeBytes: size, createdAt: now } });
    } catch (err) {
      await rm(path, { force: true }).catch(() => undefined);
      // A snaps folder the server cannot write (e.g. a volume owned by root) is a set-up problem, not the user's.
      const code = (err as { code?: string }).code;
      if (code === "EACCES" || code === "EPERM" || code === "EROFS" || code === "ENOSPC") throw appError(503, "SNAPS_UNAVAILABLE");
      throw err;
    }
    const streak = await this.recordStreak(connection, userId, now);
    this.events?.publish([userId, recipientUserId], { type: "snap.changed", connectionId });
    void this.push?.notifyUser(recipientUserId, { t: "snap" }, { highPriority: false, ttlSeconds: 24 * 3600 }).catch(() => undefined);
    return { id, streak };
  }

  /** Snaps waiting for [userId], oldest first. */
  async inbox(userId: string): Promise<SnapView[]> {
    const rows = await this.prisma.snap.findMany({
      where: { recipientUserId: userId, createdAt: { gt: new Date(this.now().getTime() - SNAP_POLICY.lifetimeMs) } },
      include: { sender: { select: { id: true, username: true } } },
      orderBy: { createdAt: "asc" },
    });
    return rows.map((s) => ({ id: s.id, connectionId: s.connectionId, from: s.sender, kind: s.kind as SnapKind, createdAt: s.createdAt }));
  }

  /** Opens a snap: returns its bytes once and deletes it from the server at the same moment. */
  async open(userId: string, snapId: string): Promise<{ kind: SnapKind; bytes: Buffer }> {
    const snap = await this.prisma.snap.findUnique({ where: { id: snapId } });
    if (!snap || snap.recipientUserId !== userId) throw appError(404, "NOT_FOUND");
    // Only one request can win the delete, so a snap is never handed out twice.
    const gone = await this.prisma.snap.deleteMany({ where: { id: snapId } });
    if (gone.count !== 1) throw appError(404, "NOT_FOUND");
    const path = this.file(snapId);
    try {
      return { kind: snap.kind as SnapKind, bytes: await readFile(path) };
    } catch {
      throw appError(404, "NOT_FOUND");
    } finally {
      await rm(path, { force: true });
      this.events?.publish([snap.senderUserId, snap.recipientUserId], { type: "snap.changed", connectionId: snap.connectionId });
    }
  }

  /** Each connection's current streak for [userId]'s chat list (0 once a day was missed). */
  async streaks(connectionIds: string[]): Promise<Map<string, number>> {
    if (connectionIds.length === 0) return new Map();
    const today = streakDay(this.now());
    const yesterday = previousDay(today);
    const rows = await this.prisma.snapStreak.findMany({ where: { connectionId: { in: connectionIds } } });
    return new Map(rows.map((r) => [r.connectionId, r.lastMutualDay === today || r.lastMutualDay === yesterday ? r.count : 0]));
  }

  /** How many snaps wait for [userId], per connection. */
  async pendingCounts(userId: string): Promise<Map<string, number>> {
    const rows = await this.prisma.snap.groupBy({
      by: ["connectionId"],
      where: { recipientUserId: userId, createdAt: { gt: new Date(this.now().getTime() - SNAP_POLICY.lifetimeMs) } },
      _count: { _all: true },
    });
    return new Map(rows.map((r) => [r.connectionId, r._count._all]));
  }

  /** Deletes snaps nobody opened within 24 h. */
  async sweep(): Promise<number> {
    if (!this.dir) return 0;
    const old = await this.prisma.snap.findMany({ where: { createdAt: { lt: new Date(this.now().getTime() - SNAP_POLICY.lifetimeMs) } }, select: { id: true } });
    for (const s of old) {
      await this.prisma.snap.deleteMany({ where: { id: s.id } });
      await rm(this.file(s.id), { force: true });
    }
    return old.length;
  }

  /**
   * A streak counts the days in a row on which both people sent each other at least one snap (Snapchat's
   * rule). Sending sets the sender's day; when both have sent today the streak grows by one, or starts at 1
   * if yesterday was missed.
   */
  private async recordStreak(connection: { id: string; userAId: string }, senderId: string, at: Date): Promise<number> {
    const today = streakDay(at);
    const yesterday = previousDay(today);
    return this.prisma.$transaction(async (tx) => {
      await tx.$executeRaw`INSERT INTO snap_streaks (connection_id) VALUES (${connection.id}::uuid) ON CONFLICT DO NOTHING`;
      const rows = await tx.$queryRaw<{ count: number; last_mutual_day: string | null; user_a_day: string | null; user_b_day: string | null }[]>`
        SELECT count, last_mutual_day, user_a_day, user_b_day FROM snap_streaks WHERE connection_id = ${connection.id}::uuid FOR UPDATE`;
      const s = rows[0]!;
      const isA = connection.userAId === senderId;
      const otherDay = isA ? s.user_b_day : s.user_a_day;
      let count = s.count;
      let mutual = s.last_mutual_day;
      if (otherDay === today && mutual !== today) {
        count = mutual === yesterday ? count + 1 : 1;
        mutual = today;
      }
      await tx.snapStreak.update({
        where: { connectionId: connection.id },
        data: { count, lastMutualDay: mutual, ...(isA ? { userADay: today } : { userBDay: today }) },
      });
      return mutual === today || mutual === yesterday ? count : 0;
    });
  }

  private limit(userId: string, now: Date) {
    const t = now.getTime();
    const w = this.sends.get(userId);
    if (!w || t - w.windowStart >= 60_000) {
      this.sends.set(userId, { windowStart: t, count: 1 });
      return;
    }
    if (++w.count > SNAP_POLICY.sendsPerMinute) throw appError(429, "RATE_LIMITED");
  }
}
