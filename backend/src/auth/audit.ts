import type { Prisma, PrismaClient } from "../generated/prisma/client.js";

type Db = PrismaClient | Prisma.TransactionClient;

export interface AuditInput {
  actorType: "user" | "admin" | "system";
  actorId?: string | null;
  action: string;
  targetType?: string;
  targetId?: string | null;
  ip?: string | null;
  /** Never secrets, passwords, tokens, message text or coordinates. */
  metadata?: Prisma.InputJsonValue;
}

export async function audit(db: Db, e: AuditInput): Promise<void> {
  await db.auditEvent.create({
    data: {
      actorType: e.actorType,
      actorId: e.actorId ?? null,
      action: e.action,
      targetType: e.targetType ?? null,
      targetId: e.targetId ?? null,
      ipTruncated: e.ip ?? null,
      ...(e.metadata !== undefined ? { metadata: e.metadata } : {}),
    },
  });
}
