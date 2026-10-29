import type { PrismaClient, Prisma } from "../generated/prisma/client.js";
import { audit } from "../auth/audit.js";
import { generateToken, hashToken } from "../auth/tokens.js";
import { AppError, appError, DEFAULT_MESSAGES } from "../errors.js";
import type { EventPublisher } from "../realtime/events.js";

const SECOND = 1000;
const MINUTE = 60 * SECOND;

/** QR policy (docs/06-qr-connection.md §2). */
export const QR_POLICY = {
  tokenTtlMs: 60 * SECOND,
  requestTtlMs: 5 * MINUTE,
  qrPerMinute: 6,
  qrPerHour: 60,
  redeemPerMinute: 10,
};

export const QR_SCHEME = "vaultchat://connect";

export type RequestStatus = "pending" | "accepted" | "rejected" | "cancelled" | "expired";

export interface RequestView {
  id: string;
  direction: "incoming" | "outgoing";
  peer: { username: string };
  status: RequestStatus;
  createdAt: Date;
  expiresAt: Date;
}

export interface ConnectionView {
  id: string;
  peer: { id: string; username: string };
  conversationId: string;
  createdAt: Date;
}

/** A row of the app's chat list: the connection plus what the user has not read yet. */
export interface ConnectionSummary extends ConnectionView {
  unreadCount: number;
  lastMessageAt: Date | null;
}

/** Fixed-window per-user counter, in memory (single backend instance, docs/02 ADR-07). */
export class UserRateLimiter {
  private readonly windows = new Map<string, { start: number; count: number }>();
  constructor(private readonly limit: number, private readonly windowMs: number) {}

  /** Returns seconds to wait, or 0 if the action is allowed (and counts it). */
  take(key: string, now: number): number {
    const w = this.windows.get(key);
    if (!w || now - w.start >= this.windowMs) {
      this.windows.set(key, { start: now, count: 1 });
      return 0;
    }
    if (w.count >= this.limit) return Math.ceil((w.start + this.windowMs - now) / 1000);
    w.count += 1;
    return 0;
  }
}

/** QR payload carries only the random token (docs/06 §2): no username, id or password. */
export function qrPayload(token: string): string {
  return `${QR_SCHEME}?v=1&t=${token}`;
}

export class ConnectionService {
  private readonly qrMinute = new UserRateLimiter(QR_POLICY.qrPerMinute, MINUTE);
  private readonly qrHour = new UserRateLimiter(QR_POLICY.qrPerHour, 60 * MINUTE);
  private readonly redeemMinute = new UserRateLimiter(QR_POLICY.redeemPerMinute, MINUTE);

  constructor(
    private readonly prisma: PrismaClient,
    private readonly now: () => Date = () => new Date(),
    /** Live "request changed" signals to both people, so pairing screens update at once. */
    private readonly events: EventPublisher | null = null,
  ) {}

  private changed(requestId: string, userIds: readonly string[]) {
    this.events?.publish(userIds, { type: "connection.changed", requestId });
  }

  /** The user's oldest connection; what apps from before D-21 (one contact) show. */
  async connectionFor(userId: string): Promise<ConnectionView | null> {
    return (await this.connectionsFor(userId))[0] ?? null;
  }

  /** Every connection of the user, oldest first (D-21: up to MAX_USERS - 1 contacts). */
  async connectionsFor(userId: string): Promise<ConnectionView[]> {
    const rows = await this.prisma.connection.findMany({
      where: { OR: [{ userAId: userId }, { userBId: userId }] },
      include: { userA: true, userB: true, conversation: { select: { id: true } } },
      orderBy: { createdAt: "asc" },
    });
    return rows.flatMap((c) => {
      if (!c.conversation) return [];
      const peer = c.userAId === userId ? c.userB : c.userA;
      return [{ id: c.id, peer: { id: peer.id, username: peer.username }, conversationId: c.conversation.id, createdAt: c.createdAt }];
    });
  }

  /** The chat list: each connection with the count of unread messages from the peer and the latest message time. */
  async summariesFor(userId: string): Promise<ConnectionSummary[]> {
    const views = await this.connectionsFor(userId);
    if (views.length === 0) return [];
    const ids = views.map((v) => v.conversationId);
    const stats = await this.prisma.$queryRaw<{ conversation_id: string; unread: bigint; last_at: Date | null }[]>`
      SELECT p.conversation_id,
             COUNT(m.id) FILTER (WHERE m.seq > p.last_read_seq AND m.sender_user_id <> ${userId}::uuid) AS unread,
             MAX(m.created_at) AS last_at
      FROM conversation_participants p
      LEFT JOIN messages m ON m.conversation_id = p.conversation_id
      WHERE p.user_id = ${userId}::uuid AND p.conversation_id = ANY(${ids}::uuid[])
      GROUP BY p.conversation_id`;
    const byId = new Map(stats.map((s) => [s.conversation_id, s]));
    return views.map((v) => {
      const s = byId.get(v.conversationId);
      return { ...v, unreadCount: Number(s?.unread ?? 0), lastMessageAt: s?.last_at ?? null };
    });
  }

  /** Issues a fresh single-use QR token and invalidates the owner's previous unused one. */
  async generateQr(userId: string): Promise<{ token: string; payload: string; expiresAt: Date }> {
    const now = this.now();
    this.limit(this.qrMinute, userId, now);
    this.limit(this.qrHour, userId, now);
    // Since D-21 a user may connect with several people; redeem still refuses an existing pair.

    const token = generateToken("vcq_");
    const expiresAt = new Date(now.getTime() + QR_POLICY.tokenTtlMs);
    await this.prisma.$transaction(async (tx) => {
      await tx.qrToken.updateMany({
        where: { ownerUserId: userId, consumedAt: null, supersededAt: null },
        data: { supersededAt: now },
      });
      await tx.qrToken.create({ data: { ownerUserId: userId, tokenHash: hashToken(token), createdAt: now, expiresAt } });
    });
    return { token, payload: qrPayload(token), expiresAt };
  }

  /** Redeems a scanned token into a pending request for the QR owner to answer. */
  async redeem(userId: string, token: string, ip: string | null): Promise<RequestView> {
    const now = this.now();
    this.limit(this.redeemMinute, userId, now);

    const request = await this.prisma.$transaction(async (tx) => {
      // One conditional UPDATE: of two simultaneous scans of one token, exactly one succeeds.
      const consumed = await tx.qrToken.updateMany({
        where: {
          tokenHash: hashToken(token),
          consumedAt: null,
          supersededAt: null,
          expiresAt: { gt: now },
          ownerUserId: { not: userId },
          owner: { status: "active" },
        },
        data: { consumedAt: now, consumedByUserId: userId },
      });
      // Same error for unknown, expired, used, superseded or own token: no oracle.
      if (consumed.count !== 1) throw appError(410, "QR_INVALID_OR_EXPIRED");
      const qr = await tx.qrToken.findUniqueOrThrow({ where: { tokenHash: hashToken(token) } });

      if (await this.connectionBetween(tx, userId, qr.ownerUserId)) throw appError(409, "ALREADY_CONNECTED");
      await this.expireStale(tx, now, { requesterUserId: userId, ownerUserId: qr.ownerUserId });
      const pending = await tx.connectionRequest.findFirst({
        where: { requesterUserId: userId, ownerUserId: qr.ownerUserId, status: "pending" },
      });
      if (pending) throw appError(409, "REQUEST_ALREADY_PENDING");

      const created = await tx.connectionRequest.create({
        data: {
          qrTokenId: qr.id,
          requesterUserId: userId,
          ownerUserId: qr.ownerUserId,
          createdAt: now,
          expiresAt: new Date(now.getTime() + QR_POLICY.requestTtlMs),
        },
        include: { owner: true },
      });
      await audit(tx, { actorType: "user", actorId: userId, action: "connection.request_created", targetType: "connection_request", targetId: created.id, ip });
      return created;
    });
    this.changed(request.id, [request.requesterUserId, request.ownerUserId]);

    return {
      id: request.id,
      direction: "outgoing",
      peer: { username: request.owner.username },
      status: "pending",
      createdAt: request.createdAt,
      expiresAt: request.expiresAt,
    };
  }

  async listRequests(userId: string, filter: { direction?: "incoming" | "outgoing"; status?: RequestStatus }): Promise<RequestView[]> {
    const now = this.now();
    await this.expireStale(this.prisma, now, { OR: [{ requesterUserId: userId }, { ownerUserId: userId }] });
    const where: Prisma.ConnectionRequestWhereInput = {
      ...(filter.direction === "incoming"
        ? { ownerUserId: userId }
        : filter.direction === "outgoing"
          ? { requesterUserId: userId }
          : { OR: [{ requesterUserId: userId }, { ownerUserId: userId }] }),
      ...(filter.status ? { status: filter.status } : {}),
    };
    const rows = await this.prisma.connectionRequest.findMany({
      where,
      include: { requester: true, owner: true },
      orderBy: { createdAt: "desc" },
      take: 20,
    });
    return rows.map((r) => {
      const incoming = r.ownerUserId === userId;
      return {
        id: r.id,
        direction: incoming ? "incoming" : "outgoing",
        peer: { username: incoming ? r.requester.username : r.owner.username },
        status: r.status as RequestStatus,
        createdAt: r.createdAt,
        expiresAt: r.expiresAt,
      };
    });
  }

  /** The QR owner accepts: creates the permanent connection (docs/06 §4.3). */
  async accept(userId: string, requestId: string, ip: string | null): Promise<ConnectionView> {
    const now = this.now();
    const pair = await this.prisma.$transaction(async (tx) => {
      const peek = await tx.connectionRequest.findUnique({ where: { id: requestId } });
      if (!peek || peek.ownerUserId !== userId) throw appError(404, "NOT_FOUND");
      // Lock every request between this pair in one consistent order (by id). Two users accepting
      // each other's requests at once would otherwise deadlock (each holding one row lock).
      const locked = await this.lockPairRequests(tx, peek.requesterUserId, peek.ownerUserId);
      const req = locked.find((r) => r.id === requestId);
      if (!req) throw appError(404, "NOT_FOUND");
      if (req.status !== "pending" || req.expires_at <= now) throw appError(409, "REQUEST_NOT_PENDING");
      const requester = await tx.user.findUnique({ where: { id: req.requester_user_id } });
      if (!requester || requester.status !== "active") throw appError(403, "ACCOUNT_DISABLED");

      const [a, b] = [req.requester_user_id, req.owner_user_id].sort();
      // skipDuplicates = ON CONFLICT DO NOTHING: simultaneous accepts in both directions keep one row.
      const created = await tx.connection.createMany({ data: [{ userAId: a!, userBId: b!, createdFromRequestId: req.id, createdAt: now }], skipDuplicates: true });
      if (created.count === 1) {
        // The connection's conversation and both participants come into existence with it.
        const connection = await tx.connection.findUniqueOrThrow({ where: { userAId_userBId: { userAId: a!, userBId: b! } } });
        await tx.conversation.create({
          data: { connectionId: connection.id, participants: { create: [{ userId: a! }, { userId: b! }] } },
        });
      }
      await tx.connectionRequest.update({ where: { id: req.id }, data: { status: "accepted", decidedAt: now } });
      await tx.connectionRequest.updateMany({
        where: {
          status: "pending",
          id: { not: req.id },
          OR: [
            { requesterUserId: a!, ownerUserId: b! },
            { requesterUserId: b!, ownerUserId: a! },
          ],
        },
        data: { status: "cancelled", decidedAt: now },
      });
      await audit(tx, { actorType: "user", actorId: userId, action: "connection.accepted", targetType: "connection_request", targetId: req.id, ip });
      return [a!, b!];
    });
    // Cross-scan requests cancelled above are covered: both people re-read their requests.
    this.changed(requestId, pair);
    const view = (await this.connectionsFor(userId)).find((c) => c.peer.id === (pair[0] === userId ? pair[1] : pair[0]));
    if (!view) throw appError(409, "CONFLICT");
    return view;
  }

  async reject(userId: string, requestId: string, ip: string | null): Promise<void> {
    await this.decide(userId, requestId, "owner", "rejected", "connection.rejected", ip);
  }

  async cancel(userId: string, requestId: string, ip: string | null): Promise<void> {
    await this.decide(userId, requestId, "requester", "cancelled", "connection.request_cancelled", ip);
  }

  /** Housekeeping: drops spent QR tokens after 24 h (docs/06 §7). */
  async cleanup(): Promise<number> {
    const cutoff = new Date(this.now().getTime() - 24 * 60 * MINUTE);
    const r = await this.prisma.qrToken.deleteMany({ where: { expiresAt: { lt: cutoff } } });
    return r.count;
  }

  // ---------------------------------------------------------------------------------------------

  private async decide(
    userId: string,
    requestId: string,
    role: "owner" | "requester",
    status: "rejected" | "cancelled",
    action: string,
    ip: string | null,
  ) {
    const now = this.now();
    const pair = await this.prisma.$transaction(async (tx) => {
      const req = await this.lockRequest(tx, requestId);
      const actorId = role === "owner" ? req?.owner_user_id : req?.requester_user_id;
      if (!req || actorId !== userId) throw appError(404, "NOT_FOUND");
      if (req.status !== "pending" || req.expires_at <= now) throw appError(409, "REQUEST_NOT_PENDING");
      await tx.connectionRequest.update({ where: { id: req.id }, data: { status, decidedAt: now } });
      await audit(tx, { actorType: "user", actorId: userId, action, targetType: "connection_request", targetId: req.id, ip });
      return [req.owner_user_id, req.requester_user_id];
    });
    this.changed(requestId, pair);
  }

  private async lockRequest(tx: Prisma.TransactionClient, id: string) {
    const rows = await tx.$queryRaw<
      { id: string; requester_user_id: string; owner_user_id: string; status: string; expires_at: Date }[]
    >`SELECT id, requester_user_id, owner_user_id, status, expires_at
      FROM connection_requests WHERE id = ${id}::uuid FOR UPDATE`;
    return rows[0] ?? null;
  }

  private async lockPairRequests(tx: Prisma.TransactionClient, x: string, y: string) {
    return tx.$queryRaw<
      { id: string; requester_user_id: string; owner_user_id: string; status: string; expires_at: Date }[]
    >`SELECT id, requester_user_id, owner_user_id, status, expires_at
      FROM connection_requests
      WHERE (requester_user_id = ${x}::uuid AND owner_user_id = ${y}::uuid)
         OR (requester_user_id = ${y}::uuid AND owner_user_id = ${x}::uuid)
      ORDER BY id
      FOR UPDATE`;
  }

  private async connectionBetween(tx: Prisma.TransactionClient, x: string, y: string) {
    const [a, b] = [x, y].sort();
    return tx.connection.findUnique({ where: { userAId_userBId: { userAId: a!, userBId: b! } } });
  }

  /** Lazy expiry keeps the "one pending request" index accurate without a background job. */
  private async expireStale(db: PrismaClient | Prisma.TransactionClient, now: Date, where: Prisma.ConnectionRequestWhereInput) {
    await db.connectionRequest.updateMany({
      where: { ...where, status: "pending", expiresAt: { lte: now } },
      data: { status: "expired", decidedAt: now },
    });
  }

  private limit(limiter: UserRateLimiter, userId: string, now: Date) {
    const wait = limiter.take(userId, now.getTime());
    if (wait > 0) {
      throw new AppError(429, "RATE_LIMITED", DEFAULT_MESSAGES.RATE_LIMITED, undefined, wait);
    }
  }
}
