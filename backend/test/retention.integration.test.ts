import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { PrismaDatabase } from "../src/db/database.js";
import { RetentionService } from "../src/retention/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

describe.skipIf(!TEST_DB_URL)("retention, D-12 (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  const NOW = new Date("2030-06-01T12:00:00Z");
  const ago = (ms: number) => new Date(NOW.getTime() - ms);
  const HOUR = 3600_000;

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
  });

  it("deletes ended calls (with diagnostics) after 24 h and audit events after 365 days", async () => {
    const db = database.prisma;
    const a = await db.user.create({ data: { slot: 1, username: "alice", passwordHash: "x", passwordChangedAt: NOW } });
    const b = await db.user.create({ data: { slot: 2, username: "bob", passwordHash: "x", passwordChangedAt: NOW } });
    const [ua, ub] = [a.id, b.id].sort();
    const conn = await db.connection.create({ data: { userAId: ua!, userBId: ub! } });
    const call = (status: string, createdAt: Date, room: string) =>
      db.call.create({ data: { connectionId: conn.id, callerUserId: a.id, calleeUserId: b.id, kind: "video", livekitRoom: room, status, createdAt } });

    const old = await call("ended", ago(25 * HOUR), "r1");
    await db.callDiagnostic.create({ data: { callId: old.id, userId: a.id, summary: { durationSec: 10 } } });
    await call("missed", ago(25 * HOUR), "r2");
    const recent = await call("ended", ago(23 * HOUR), "r3");
    // A live call is never removed, however old (only possible if the sweep failed).
    const live = await call("ringing", ago(30 * HOUR), "r4");

    await db.auditEvent.createMany({
      data: [
        { actorType: "system", action: "old", occurredAt: ago(366 * 24 * HOUR) },
        { actorType: "system", action: "recent", occurredAt: ago(364 * 24 * HOUR) },
      ],
    });

    const retention = new RetentionService(db, () => NOW);
    expect(await retention.purge()).toMatchObject({ calls: 2 });
    expect((await db.auditEvent.count())).toBe(2); // the app job never touches the audit log
    expect(await retention.purgeAudit()).toBe(1);
    expect((await db.call.findMany({ orderBy: { livekitRoom: "asc" } })).map((c) => c.id)).toEqual([recent.id, live.id]);
    expect(await db.callDiagnostic.count()).toBe(0);
    expect((await db.auditEvent.findMany()).map((e) => e.action)).toEqual(["recent"]);
    // Nothing else is touched.
    expect(await db.user.count()).toBe(2);
    expect(await db.connection.count()).toBe(1);
    expect(await retention.purge()).toMatchObject({ calls: 0 });
    expect(await retention.purgeAudit()).toBe(0);
  });

  it("deletes old sessions, admin sessions and finished requests, never the request behind a connection", async () => {
    const db = database.prisma;
    const a = await db.user.create({ data: { slot: 1, username: "alice", passwordHash: "x", passwordChangedAt: NOW } });
    const b = await db.user.create({ data: { slot: 2, username: "bob", passwordHash: "x", passwordChangedAt: NOW } });
    let n = 0;
    const session = (over: { revokedAt?: Date; idleExpiresAt?: Date; absoluteExpiresAt?: Date }) =>
      db.session.create({
        data: {
          userId: a.id,
          accessTokenHash: Uint8Array.from([++n, 1]),
          refreshTokenHash: Uint8Array.from([n, 2]),
          accessExpiresAt: NOW,
          idleExpiresAt: over.idleExpiresAt ?? new Date(NOW.getTime() + 30 * 24 * HOUR),
          absoluteExpiresAt: over.absoluteExpiresAt ?? new Date(NOW.getTime() + 90 * 24 * HOUR),
          ...(over.revokedAt ? { revokedAt: over.revokedAt } : {}),
        },
      });
    await session({ revokedAt: ago(31 * 24 * HOUR) });
    await session({ idleExpiresAt: ago(31 * 24 * HOUR) });
    const keepRevoked = await session({ revokedAt: ago(29 * 24 * HOUR) });
    const active = await session({});

    const admin = await db.adminAccount.create({ data: { username: "root", passwordHash: "x", totpSecretEnc: Uint8Array.from([1]) } });
    const adminSession = (idle: Date, t: string) =>
      db.adminSession.create({ data: { adminId: admin.id, tokenHash: Uint8Array.from([t.charCodeAt(0)]), csrfToken: t, idleExpiresAt: idle, absoluteExpiresAt: idle } });
    await adminSession(ago(8 * 24 * HOUR), "o");
    const keepAdmin = await adminSession(ago(6 * 24 * HOUR), "k");

    const request = (status: string, decidedAt: Date | null, expiresAt: Date) =>
      db.connectionRequest.create({ data: { requesterUserId: b.id, ownerUserId: a.id, status, decidedAt, expiresAt } });
    await request("rejected", ago(31 * 24 * HOUR), ago(31 * 24 * HOUR));
    await request("expired", ago(31 * 24 * HOUR), ago(31 * 24 * HOUR));
    const keepRecent = await request("cancelled", ago(29 * 24 * HOUR), ago(29 * 24 * HOUR));
    const accepted = await request("accepted", ago(60 * 24 * HOUR), ago(60 * 24 * HOUR));
    const [ua, ub] = [a.id, b.id].sort();
    await db.connection.create({ data: { userAId: ua!, userBId: ub!, createdFromRequestId: accepted.id } });

    const purged = await new RetentionService(db, () => NOW).purge();
    expect(purged).toMatchObject({ sessions: 2, adminSessions: 1, connectionRequests: 2 });
    expect((await db.session.findMany()).map((x) => x.id).sort()).toEqual([keepRevoked.id, active.id].sort());
    expect((await db.adminSession.findMany()).map((x) => x.id)).toEqual([keepAdmin.id]);
    expect((await db.connectionRequest.findMany()).map((x) => x.id).sort()).toEqual([keepRecent.id, accepted.id].sort());
    expect(await db.connection.count()).toBe(1);
  });
});
