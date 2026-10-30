import { randomUUID } from "node:crypto";
import type { PrismaClient, Prisma } from "../generated/prisma/client.js";
import { UserRateLimiter } from "../connections/service.js";
import { AppError, appError, DEFAULT_MESSAGES } from "../errors.js";
import type { PushService } from "../push/service.js";
import type { EventPublisher } from "../realtime/events.js";
import type { SettingsService } from "../settings/service.js";

const SECOND = 1000;
const MINUTE = 60 * SECOND;

/**
 * Chat policy: docs/07-chat-and-disappearing-messages.md, decisions D-01 (option D) and D-02
 * (60 s presence lease renewed every 20 s; every message deleted at most 24 h after it was sent).
 */
export const CHAT_POLICY = {
  leaseTtlMs: 60 * SECOND,
  heartbeatSeconds: 20,
  backstopMs: 24 * 60 * MINUTE,
  maxCodePoints: 4000,
  sendsPerMinute: 30,
  /** Reaction changes per user per minute (D-25). */
  reactionsPerMinute: 60,
  pageSize: 200,
  /** At most one "new message" push per recipient in this interval (the push carries no content). */
  messagePushIntervalMs: MINUTE,
};

export interface EnterView {
  leaseId: string;
  leaseTtlSeconds: number;
  heartbeatSeconds: number;
  epoch: number;
}

export interface MessageView {
  id: string;
  seq: number;
  senderId: string;
  text: string;
  createdAt: Date;
}

/** One person's reaction on one message (D-25). */
export interface ReactionView {
  messageId: string;
  userId: string;
  emoji: string;
}

export interface HistoryView {
  epoch: number;
  /** The caller's epoch was stale: it must drop everything it holds; `items` start from the beginning. */
  cleared: boolean;
  items: MessageView[];
  hasMore: boolean;
  /**
   * Every reaction in the conversation now, not only on `items`: history is read incrementally, so
   * a reaction added to an older message would otherwise never be seen. Small, because seen
   * messages are deleted on exit.
   */
  reactions: ReactionView[];
}

export interface SentView {
  id: string;
  seq: number;
  epoch: number;
  createdAt: Date;
  /** False when this was an idempotent retry of a message that already exists. */
  created: boolean;
}

type Tx = Prisma.TransactionClient;

/** Trimmed message text, or an error that is safe to return. Counted in Unicode code points. */
export function normalizeText(text: string): string {
  const trimmed = text.trim();
  if (trimmed.length === 0) throw appError(400, "VALIDATION_FAILED", [{ path: "/text", rule: "nonEmpty" }]);
  // PostgreSQL text cannot hold NUL; refuse it rather than fail with a 500.
  if (trimmed.includes("\u0000")) throw appError(400, "VALIDATION_FAILED", [{ path: "/text", rule: "noNul" }]);
  if ([...trimmed].length > CHAT_POLICY.maxCodePoints) throw appError(422, "MESSAGE_TOO_LONG");
  return trimmed;
}

// `\p{RGI_Emoji}` needs the `v` flag (ES2024; Node 20+). Built from a string because the compile
// target is ES2023, which rejects the flag in a regex literal.
const SINGLE_EMOJI = new RegExp("^\\p{RGI_Emoji}$", "v");

/** Exactly one emoji (one RGI emoji sequence, skin tones and ZWJ families included), or an error. */
export function normalizeReaction(emoji: string): string {
  if (!SINGLE_EMOJI.test(emoji)) throw appError(400, "VALIDATION_FAILED", [{ path: "/emoji", rule: "singleEmoji" }]);
  return emoji;
}

export class ChatService {
  private readonly sendLimiter = new UserRateLimiter(CHAT_POLICY.sendsPerMinute, MINUTE);
  private readonly reactionLimiter = new UserRateLimiter(CHAT_POLICY.reactionsPerMinute, MINUTE);
  private readonly lastMessagePush = new Map<string, number>();

  constructor(
    private readonly prisma: PrismaClient,
    private readonly now: () => Date = () => new Date(),
    private readonly push: PushService | null = null,
    private readonly events: EventPublisher | null = null,
    /** Admin Console settings: may shorten the 24 h backstop (never lengthen it, D-02). */
    private readonly settings: SettingsService | null = null,
  ) {}

  /** Tells both participants' open apps to re-read the conversation (new message or deletions). */
  private changed(conversationId: string) {
    if (!this.events) return;
    const events = this.events;
    void this.prisma.conversationParticipant
      .findMany({ where: { conversationId }, select: { userId: true } })
      .then((ps) => events.publish(ps.map((p) => p.userId), { type: "chat.changed", conversationId }))
      .catch(() => undefined);
  }

  /** Opens the chat: starts (or replaces) the caller's presence lease. */
  async enter(userId: string, conversationId: string): Promise<EnterView> {
    const now = this.now();
    const leaseId = randomUUID();
    const { epoch, deleted } = await this.prisma.$transaction(async (tx) => {
      await this.lockConversation(tx, conversationId);
      const p = await this.participant(tx, conversationId, userId);
      // A lease that lapsed but was not yet swept is an exit that already happened (D-02).
      const removed = p.leaseExpiresAt && p.leaseExpiresAt <= now ? await this.clearSeen(tx, conversationId, now) : 0;
      await this.setLease(tx, conversationId, userId, leaseId, now);
      return { epoch: await this.epochOf(tx, conversationId), deleted: removed };
    });
    if (deleted > 0) this.changed(conversationId);
    return { leaseId, leaseTtlSeconds: CHAT_POLICY.leaseTtlMs / SECOND, heartbeatSeconds: CHAT_POLICY.heartbeatSeconds, epoch };
  }

  /** Renews the lease. A lapsed or replaced lease is refused: the client must enter again. */
  async heartbeat(userId: string, conversationId: string, leaseId: string): Promise<void> {
    const now = this.now();
    let exited = false;
    const ok = await this.prisma.$transaction(async (tx) => {
      await this.lockConversation(tx, conversationId);
      const p = await this.participant(tx, conversationId, userId);
      if (p.leaseId !== leaseId) return false;
      if (!p.leaseExpiresAt || p.leaseExpiresAt <= now) {
        await this.exit(tx, conversationId, userId, now);
        exited = true;
        return false;
      }
      await this.setLease(tx, conversationId, userId, leaseId, now);
      return true;
    });
    // Published only after the commit, so the apps re-read the new state.
    if (exited) this.changed(conversationId);
    if (!ok) throw appError(409, "LEASE_EXPIRED");
  }

  /** Explicit exit (Back, toolbar, logout). Idempotent by `leaveId`. */
  async leave(userId: string, conversationId: string, leaveId: string): Promise<{ epoch: number }> {
    const now = this.now();
    let exited = false;
    const epoch = await this.prisma.$transaction(async (tx) => {
      await this.lockConversation(tx, conversationId);
      const p = await this.participant(tx, conversationId, userId);
      if (p.lastLeaveId !== leaveId) {
        exited = true;
        await this.exit(tx, conversationId, userId, now);
        await tx.conversationParticipant.update({
          where: { conversationId_userId: { conversationId, userId } },
          data: { lastLeaveId: leaveId },
        });
      }
      return this.epochOf(tx, conversationId);
    });
    if (exited) this.changed(conversationId);
    return { epoch };
  }

  async send(userId: string, conversationId: string, input: { clientMessageId: string; epoch: number; text: string }): Promise<SentView> {
    const body = normalizeText(input.text);
    const now = this.now();
    const result = await this.prisma.$transaction(async (tx) => {
      const conv = await this.lockConversation(tx, conversationId);
      await this.participant(tx, conversationId, userId);
      // A retry of a message that still exists returns it. One that a clear deleted no longer
      // exists, and its stale epoch is refused below, so it can never come back.
      const existing = await tx.message.findUnique({
        where: { conversationId_senderUserId_clientMessageId: { conversationId, senderUserId: userId, clientMessageId: input.clientMessageId } },
      });
      if (existing) {
        return { id: existing.id, seq: Number(existing.seq), epoch: Number(existing.epoch), createdAt: existing.createdAt, created: false };
      }
      if (BigInt(input.epoch) !== conv.clear_epoch) throw appError(409, "CONVERSATION_CLEARED");
      this.limitSends(userId, now);

      const seq = conv.last_seq + 1n;
      const message = await tx.message.create({
        data: { conversationId, seq, epoch: conv.clear_epoch, senderUserId: userId, clientMessageId: input.clientMessageId, body, createdAt: now },
      });
      await tx.conversation.update({ where: { id: conversationId }, data: { lastSeq: seq } });
      return { id: message.id, seq: Number(seq), epoch: Number(conv.clear_epoch), createdAt: message.createdAt, created: true };
    }).catch((err: unknown) => {
      // Database errors can quote the statement's arguments; the message text must never reach the logs.
      if (err instanceof AppError) throw err;
      throw new Error(`message send failed (${(err as { code?: string }).code ?? "unknown"})`);
    });
    if (result.created) {
      this.changed(conversationId);
      void this.notifyPeer(userId, conversationId, now).catch(() => undefined);
    }
    return result;
  }

  /** Messages after `afterSeq`. If `epoch` is stale the caller must drop its state; items then start at 0. */
  async history(userId: string, conversationId: string, q: { afterSeq: number; epoch?: number; limit?: number }): Promise<HistoryView> {
    const limit = Math.min(q.limit ?? CHAT_POLICY.pageSize, CHAT_POLICY.pageSize);
    // One snapshot, so the epoch and the messages always belong together.
    return this.prisma.$transaction(
      async (tx) => {
        await this.participant(tx, conversationId, userId);
        const conv = await tx.conversation.findUniqueOrThrow({ where: { id: conversationId } });
        const epoch = Number(conv.clearEpoch);
        const cleared = q.epoch !== undefined && q.epoch !== epoch;
        const rows = await tx.message.findMany({
          where: { conversationId, seq: { gt: BigInt(cleared ? 0 : q.afterSeq) } },
          orderBy: { seq: "asc" },
          take: limit + 1,
        });
        return {
          epoch,
          cleared,
          hasMore: rows.length > limit,
          items: rows.slice(0, limit).map((m) => ({ id: m.id, seq: Number(m.seq), senderId: m.senderUserId, text: m.body, createdAt: m.createdAt })),
          reactions: await this.reactionsOf(tx, conversationId),
        };
      },
      { isolationLevel: "RepeatableRead" },
    );
  }

  /**
   * Sets (or with `null`, removes) the caller's reaction on a message (D-25). One emoji per person per
   * message; the sender may react to their own message too. A message that is gone (deleted on an
   * exit or by the backstop) is 404, so a reaction can never bring it back.
   */
  async react(userId: string, conversationId: string, messageId: string, emoji: string | null): Promise<void> {
    const value = emoji === null ? null : normalizeReaction(emoji);
    const now = this.now();
    const changed = await this.prisma.$transaction(async (tx) => {
      await this.lockConversation(tx, conversationId);
      await this.participant(tx, conversationId, userId);
      const m = await tx.message.findFirst({ where: { id: messageId, conversationId }, select: { senderUserId: true, senderReaction: true, recipientReaction: true } });
      if (!m) throw appError(404, "NOT_FOUND");
      const mine = m.senderUserId === userId;
      if ((mine ? m.senderReaction : m.recipientReaction) === value) return false;
      this.limitReactions(userId, now);
      await tx.message.update({ where: { id: messageId }, data: mine ? { senderReaction: value } : { recipientReaction: value } });
      return true;
    });
    if (changed) this.changed(conversationId);
  }

  /** Records what the caller has displayed (monotonic; capped at the last allocated sequence). */
  async markRead(userId: string, conversationId: string, seq: number): Promise<void> {
    const updated = await this.prisma.$executeRaw`
      UPDATE conversation_participants p
         SET last_read_seq = GREATEST(p.last_read_seq, LEAST(${BigInt(seq)}, c.last_seq))
        FROM conversations c
       WHERE c.id = p.conversation_id AND p.conversation_id = ${conversationId}::uuid AND p.user_id = ${userId}::uuid`;
    if (updated !== 1) throw appError(404, "NOT_FOUND");
  }

  /**
   * Server-side exits and the backstop (D-02): lapsed leases count as exits; messages older than
   * 24 h are deleted, seen or not. Runs every few seconds; also catches up after downtime.
   */
  async sweep(): Promise<{ expiredLeases: number; backstopDeleted: number }> {
    const now = this.now();
    const lapsed = await this.prisma.conversationParticipant.findMany({
      where: { leaseExpiresAt: { lte: now } },
      select: { conversationId: true, userId: true },
    });
    let expiredLeases = 0;
    for (const { conversationId, userId } of lapsed) {
      const n = await this.prisma.$transaction(async (tx) => {
        await this.lockConversation(tx, conversationId);
        const p = await this.participant(tx, conversationId, userId);
        // Renewed or left meanwhile: nothing to do.
        if (!p.leaseExpiresAt || p.leaseExpiresAt > now) return 0;
        await this.exit(tx, conversationId, userId, now);
        return 1;
      });
      if (n > 0) this.changed(conversationId);
      expiredLeases += n;
    }

    const backstopMs = this.settings
      ? Math.min(CHAT_POLICY.backstopMs, (await this.settings.get("chat.unread_backstop_hours")) * 60 * MINUTE)
      : CHAT_POLICY.backstopMs;
    const cutoff = new Date(now.getTime() - backstopMs);
    const old = await this.prisma.message.findMany({
      where: { createdAt: { lt: cutoff } },
      distinct: ["conversationId"],
      select: { conversationId: true },
    });
    let backstopDeleted = 0;
    for (const { conversationId } of old) {
      const n = await this.prisma.$transaction(async (tx) => {
        await this.lockConversation(tx, conversationId);
        const r = await tx.message.deleteMany({ where: { conversationId, createdAt: { lt: cutoff } } });
        if (r.count > 0) await this.bumpEpoch(tx, conversationId, now);
        return r.count;
      });
      if (n > 0) this.changed(conversationId);
      backstopDeleted += n;
    }
    return { expiredLeases, backstopDeleted };
  }

  // ---------------------------------------------------------------------------------------------

  /** The exit rule (D-01 option D) plus ending the caller's lease. Caller holds the conversation lock. */
  private async exit(tx: Tx, conversationId: string, userId: string, now: Date) {
    await this.clearSeen(tx, conversationId, now);
    await tx.conversationParticipant.update({
      where: { conversationId_userId: { conversationId, userId } },
      data: { leaseId: null, leaseExpiresAt: null },
    });
  }

  /**
   * D-01 option D: delete every message its recipient has already displayed, in both directions.
   * Unseen messages stay for their recipient. Hard delete; the epoch moves only if rows went.
   */
  private async clearSeen(tx: Tx, conversationId: string, now: Date): Promise<number> {
    const deleted = await tx.$executeRaw`
      DELETE FROM messages m
       USING conversation_participants p
       WHERE m.conversation_id = ${conversationId}::uuid
         AND p.conversation_id = m.conversation_id
         AND p.user_id <> m.sender_user_id
         AND m.seq <= p.last_read_seq`;
    if (deleted > 0) await this.bumpEpoch(tx, conversationId, now);
    return deleted;
  }

  private async bumpEpoch(tx: Tx, conversationId: string, now: Date) {
    await tx.conversation.update({ where: { id: conversationId }, data: { clearEpoch: { increment: 1 }, clearedAt: now } });
  }

  /** Serialises sends, exits and the backstop on one conversation (docs/07 §3.4). */
  private async lockConversation(tx: Tx, conversationId: string) {
    const rows = await tx.$queryRaw<{ last_seq: bigint; clear_epoch: bigint }[]>`
      SELECT last_seq, clear_epoch FROM conversations WHERE id = ${conversationId}::uuid FOR UPDATE`;
    if (!rows[0]) throw appError(404, "NOT_FOUND");
    return rows[0];
  }

  private async participant(tx: Tx, conversationId: string, userId: string) {
    const p = await tx.conversationParticipant.findUnique({ where: { conversationId_userId: { conversationId, userId } } });
    if (!p) throw appError(404, "NOT_FOUND");
    return p;
  }

  /** Every reaction in the conversation, attributed to the person who made it. */
  private async reactionsOf(tx: Tx, conversationId: string): Promise<ReactionView[]> {
    const rows = await tx.message.findMany({
      where: { conversationId, OR: [{ senderReaction: { not: null } }, { recipientReaction: { not: null } }] },
      orderBy: { seq: "asc" },
      select: { id: true, senderUserId: true, senderReaction: true, recipientReaction: true },
    });
    if (rows.length === 0) return [];
    const people = await tx.conversationParticipant.findMany({ where: { conversationId }, select: { userId: true } });
    const out: ReactionView[] = [];
    for (const m of rows) {
      if (m.senderReaction) out.push({ messageId: m.id, userId: m.senderUserId, emoji: m.senderReaction });
      const other = people.find((p) => p.userId !== m.senderUserId)?.userId;
      if (m.recipientReaction && other) out.push({ messageId: m.id, userId: other, emoji: m.recipientReaction });
    }
    return out;
  }

  private async epochOf(tx: Tx, conversationId: string): Promise<number> {
    const c = await tx.conversation.findUniqueOrThrow({ where: { id: conversationId }, select: { clearEpoch: true } });
    return Number(c.clearEpoch);
  }

  private async setLease(tx: Tx, conversationId: string, userId: string, leaseId: string, now: Date) {
    await tx.conversationParticipant.update({
      where: { conversationId_userId: { conversationId, userId } },
      data: { leaseId, leaseExpiresAt: new Date(now.getTime() + CHAT_POLICY.leaseTtlMs) },
    });
  }

  private limitReactions(userId: string, now: Date) {
    const wait = this.reactionLimiter.take(userId, now.getTime());
    if (wait > 0) throw new AppError(429, "RATE_LIMITED", DEFAULT_MESSAGES.RATE_LIMITED, undefined, wait);
  }

  private limitSends(userId: string, now: Date) {
    const wait = this.sendLimiter.take(userId, now.getTime());
    if (wait > 0) throw new AppError(429, "RATE_LIMITED", DEFAULT_MESSAGES.RATE_LIMITED, undefined, wait);
  }

  /** Content-free "new message" push, only when the recipient is not in the chat, throttled. */
  private async notifyPeer(senderId: string, conversationId: string, now: Date) {
    if (!this.push?.enabled) return;
    const peer = await this.prisma.conversationParticipant.findFirst({ where: { conversationId, userId: { not: senderId } } });
    if (!peer) return;
    if (peer.leaseExpiresAt && peer.leaseExpiresAt > now) return;
    const last = this.lastMessagePush.get(peer.userId) ?? 0;
    if (now.getTime() - last < CHAT_POLICY.messagePushIntervalMs) return;
    this.lastMessagePush.set(peer.userId, now.getTime());
    await this.push.notifyUser(peer.userId, { t: "msg" }, { highPriority: false, ttlSeconds: 3600 });
  }
}
