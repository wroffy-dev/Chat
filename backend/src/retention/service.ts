import type { PrismaClient } from "../generated/prisma/client.js";

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

/**
 * Retention periods. Decision D-12 (2026-10-25): call records and call diagnostics 24 h; audit
 * events 365 days. The rest follow docs/13-database-design.md §3. Messages have their own 24 h
 * backstop (D-02, ChatService); spent QR tokens are cleaned by ConnectionService.
 *
 * The audit log is append-only for the app's database role, so its clean-up is a separate job run
 * with the maintenance role (`cli maint:purge-audit`, docs/13 §1).
 */
export const RETENTION = {
  callsMs: 24 * HOUR,
  auditMs: 365 * DAY,
  /** Sessions: 30 days after revocation or expiry. */
  sessionsMs: 30 * DAY,
  /** Admin Console sessions: 7 days after revocation or expiry. */
  adminSessionsMs: 7 * DAY,
  /** Answered, cancelled or expired connection requests: 30 days. */
  requestsMs: 30 * DAY,
};

export interface PurgeCounts {
  calls: number;
  sessions: number;
  adminSessions: number;
  connectionRequests: number;
}

export class RetentionService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly now: () => Date = () => new Date(),
  ) {}

  /** Audit events older than 365 days. Needs the maintenance role (DELETE on audit_events). */
  async purgeAudit(): Promise<number> {
    const r = await this.prisma.auditEvent.deleteMany({ where: { occurredAt: { lt: new Date(this.now().getTime() - RETENTION.auditMs) } } });
    return r.count;
  }

  /** Hard-deletes app records past their retention period. Idempotent; runs with the app role. */
  async purge(): Promise<PurgeCounts> {
    const now = this.now().getTime();
    const before = (ms: number) => new Date(now - ms);

    // Live calls are never touched (they are minutes old anyway); diagnostics go with their call.
    const calls = await this.prisma.call.deleteMany({
      where: { createdAt: { lt: before(RETENTION.callsMs) }, status: { notIn: ["ringing", "accepted", "active"] } },
    });

    const s = before(RETENTION.sessionsMs);
    const sessions = await this.prisma.session.deleteMany({
      where: { OR: [{ revokedAt: { lt: s } }, { absoluteExpiresAt: { lt: s } }, { idleExpiresAt: { lt: s } }] },
    });
    const a = before(RETENTION.adminSessionsMs);
    const adminSessions = await this.prisma.adminSession.deleteMany({
      where: { OR: [{ revokedAt: { lt: a } }, { absoluteExpiresAt: { lt: a } }, { idleExpiresAt: { lt: a } }] },
    });

    // The request a connection was created from stays: connections are immutable, so the
    // ON DELETE SET NULL on connections.created_from_request_id must never fire.
    const r = before(RETENTION.requestsMs);
    const connectionRequests = await this.prisma.connectionRequest.deleteMany({
      where: {
        connection: { is: null },
        OR: [{ status: { not: "pending" }, decidedAt: { lt: r } }, { expiresAt: { lt: r } }],
      },
    });

    return {
      calls: calls.count,
      sessions: sessions.count,
      adminSessions: adminSessions.count,
      connectionRequests: connectionRequests.count,
    };
  }
}
