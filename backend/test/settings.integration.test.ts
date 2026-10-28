import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { MediaGateway } from "../src/calls/media.js";
import { CallService } from "../src/calls/service.js";
import { ChatService } from "../src/chat/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { PushService, type PushPayload, type PushTransport } from "../src/push/service.js";
import { defaultSettings, SettingsService, type Settings } from "../src/settings/service.js";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

const defaults: Settings = defaultSettings(45);
const media: MediaGateway = {
  publicUrl: "wss://rtc.example.test",
  createRoom: async () => {},
  deleteRoom: async () => {},
  joinToken: async () => "jwt",
  verifyWebhook: async () => ({ event: "none" }),
};

describe.skipIf(!TEST_DB_URL)("admin settings (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let settings: SettingsService;
  const admin = randomUUID();

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
    settings = new SettingsService(database.prisma, defaults, 0);
  });

  /** Two users with a connection and its conversation, created directly. */
  async function pair() {
    const db = database.prisma;
    const a = await db.user.create({ data: { slot: 1, username: "alice", passwordHash: "x", passwordChangedAt: new Date() } });
    const b = await db.user.create({ data: { slot: 2, username: "bob", passwordHash: "x", passwordChangedAt: new Date() } });
    const [ua, ub] = [a.id, b.id].sort();
    const conn = await db.connection.create({ data: { userAId: ua!, userBId: ub! } });
    const conv = await db.conversation.create({ data: { connectionId: conn.id, participants: { create: [{ userId: a.id }, { userId: b.id }] } } });
    return { a, b, conn, conv };
  }

  it("starts from the defaults, validates, stores and audits changes", async () => {
    expect(await settings.all()).toEqual(defaults);
    await settings.set("call.max_video_quality", "720p", admin);
    await settings.set("call.ring_timeout_seconds", "30", admin);
    await settings.set("push.enabled", "false", admin);
    expect(await settings.all()).toEqual({ ...defaults, "call.max_video_quality": "720p", "call.ring_timeout_seconds": 30, "push.enabled": false });

    const audit = await database.prisma.auditEvent.findMany({ where: { action: "admin.setting_changed" }, orderBy: { id: "asc" } });
    expect(audit.map((e) => e.metadata)).toEqual([
      { key: "call.max_video_quality", from: "1080p", to: "720p" },
      { key: "call.ring_timeout_seconds", from: 45, to: 30 },
      { key: "push.enabled", from: true, to: false },
    ]);

    for (const [key, value] of [
      ["call.max_video_quality", "4k"],
      ["call.ring_timeout_seconds", "5"],
      ["call.ring_timeout_seconds", "30; DROP TABLE users"],
      ["chat.unread_backstop_hours", "48"], // D-02: never more than 24 h
      ["push.enabled", "yes"],
      ["app.unknown", "1"],
    ]) {
      await expect(settings.set(key!, value!, admin)).rejects.toMatchObject({ statusCode: 400 });
    }
    // The database refuses unknown keys too.
    await expect(database.prisma.appSetting.create({ data: { key: "x", value: 1 } })).rejects.toThrow();
  });

  it("calls use the quality cap and ring timeout; push can be switched off", async () => {
    const { a, conn } = await pair();
    const sent: PushPayload[] = [];
    const transport: PushTransport = { send: async (_t, p) => void sent.push(p) };
    await database.prisma.session.create({
      data: {
        userId: (await database.prisma.user.findFirstOrThrow({ where: { username: "bob" } })).id,
        accessTokenHash: Uint8Array.from([1]),
        refreshTokenHash: Uint8Array.from([2]),
        accessExpiresAt: new Date(Date.now() + 60_000),
        idleExpiresAt: new Date(Date.now() + 60_000),
        absoluteExpiresAt: new Date(Date.now() + 60_000),
        fcmToken: "fcm-token-for-bob-0123456789",
      },
    });
    const push = new PushService(database.prisma, transport, () => {}, () => settings.get("push.enabled"));
    const calls = new CallService(database.prisma, media, 45, undefined, push, null, settings);

    await settings.set("call.max_video_quality", "540p", admin);
    await settings.set("call.ring_timeout_seconds", "20", admin);
    await settings.set("push.enabled", "false", admin);
    const join = await calls.start(a.id, conn.id, "video");
    expect(join).toMatchObject({ maxVideoQuality: "540p", ringTimeoutSeconds: 20 });
    await new Promise((r) => setTimeout(r, 50));
    expect(sent).toEqual([]); // switched off

    await settings.set("push.enabled", "true", admin);
    await calls.cancel(a.id, join.callId);
    await new Promise((r) => setTimeout(r, 50));
    expect(sent).toEqual([{ t: "call_end", id: join.callId }]);
  });

  it("the message lifetime can be shortened below 24 h", async () => {
    const { a, conv } = await pair();
    await database.prisma.message.create({
      data: { conversationId: conv.id, seq: 1n, epoch: 0n, senderUserId: a.id, clientMessageId: randomUUID(), body: "two hours old", createdAt: new Date(Date.now() - 2 * 3600_000) },
    });
    const chat = new ChatService(database.prisma, undefined, null, null, settings);
    expect((await chat.sweep()).backstopDeleted).toBe(0);
    await settings.set("chat.unread_backstop_hours", "1", admin);
    expect((await chat.sweep()).backstopDeleted).toBe(1);
  });

  it("refuses apps older than the minimum version with 426, and tells any app the latest version", async () => {
    const app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth: new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 })),
      settings,
      logger: false,
    });
    await app.ready();
    const me = (version?: string) => app.inject({ method: "GET", url: "/api/v1/me", headers: version ? { "x-app-version": version } : {} });
    expect((await me("3")).statusCode).toBe(401); // allowed through to authentication
    await settings.set("app.min_supported_version_code", "5", admin);
    await settings.set("app.latest_version_code", "7", admin);
    await settings.set("app.latest_apk_sha256", "AB".repeat(32), admin);
    const old = await me("4");
    expect(old.statusCode).toBe(426);
    expect(old.json().error.code).toBe("UPGRADE_REQUIRED");
    expect((await me("5")).statusCode).toBe(401);
    expect((await me()).statusCode).toBe(401); // no header: not a version gate (it is not a security control)

    // An outdated app can still learn what to install.
    const v = await app.inject({ method: "GET", url: "/api/v1/app/version", headers: { "x-app-version": "1" } });
    expect(v.json()).toEqual({ minSupportedVersionCode: 5, latestVersionCode: 7, latestApkSha256: "ab".repeat(32), downloadAvailable: false });
    await expect(settings.set("app.latest_apk_sha256", "not-hex", admin)).rejects.toMatchObject({ statusCode: 400 });
    await app.close();
  });
});
