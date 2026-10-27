import { randomUUID } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import type { MediaGateway } from "../src/calls/media.js";
import { CallService } from "../src/calls/service.js";
import { ChatService } from "../src/chat/service.js";
import { ConnectionService } from "../src/connections/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { PushService, type PushTransport } from "../src/push/service.js";
import { RealtimeHub } from "../src/realtime/hub.js";
import { defaultSettings, SettingsService } from "../src/settings/service.js";
import { captureLogs, resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

/**
 * Cross-cutting checks from docs/12-security-and-threat-model.md §4: every API route is behind
 * authentication (and the forced password change), and a full run of the app's flows leaves no
 * password, token or message text in the logs.
 */

const LIVEKIT_TOKEN = "livekit-join-token-SENTINEL-7f3a";
const media: MediaGateway = {
  publicUrl: "wss://rtc.example.test",
  createRoom: async () => {},
  deleteRoom: async () => {},
  joinToken: async () => LIVEKIT_TOKEN,
  verifyWebhook: async () => ({ event: "none" }),
};
const pushTransport: PushTransport = { send: async () => {} };

/** Routes reachable without a session. Everything else under /api/v1 must refuse anonymous callers. */
const PUBLIC = new Set(["POST /api/v1/auth/login", "POST /api/v1/auth/refresh", "GET /api/v1/app/version"]);
/** Routes a session that must still change its password may use (docs/04 §5). */
const RESTRICTED_OK = new Set([
  "POST /api/v1/auth/logout",
  "POST /api/v1/auth/change-password",
  "PUT /api/v1/devices/push-token",
  "DELETE /api/v1/devices/push-token",
]);

describe.skipIf(!TEST_DB_URL)("security (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let app: FastifyInstance;
  let logs: string[];
  let routes: { method: string; url: string }[];
  let ip = 0;

  beforeAll(async () => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await app?.close();
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
    await app?.close();
    const captured = captureLogs(config);
    logs = captured.lines;
    const push = new PushService(database.prisma, pushTransport);
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      connections: new ConnectionService(database.prisma),
      calls: new CallService(database.prisma, media, 45, undefined, push),
      chat: new ChatService(database.prisma, undefined, push),
      media,
      push,
      realtime: { hub: new RealtimeHub() },
      settings: new SettingsService(database.prisma, defaultSettings(45)),
      logger: captured.logger,
    });
    routes = [];
    app.addHook("onRoute", (r) => {
      for (const method of [r.method].flat()) {
        if (method !== "HEAD" && r.url.startsWith("/api/v1/")) routes.push({ method, url: r.url });
      }
    });
    await app.ready();
  });

  const concrete = (url: string) => url.replace(/:id\b/g, randomUUID());
  const send = (method: string, url: string, headers: Record<string, string> = {}) =>
    app.inject({ method: method as "GET", url: concrete(url), headers, remoteAddress: `203.0.113.${++ip % 250}` });

  it("every API route except login, refresh and the version check refuses anonymous and malformed credentials", async () => {
    // Guard against a vacuous pass: the inventory must include every feature area.
    const names = routes.map((r) => `${r.method} ${r.url}`);
    expect(names.length).toBeGreaterThanOrEqual(25);
    for (const must of ["GET /api/v1/me", "POST /api/v1/connection-qr", "POST /api/v1/calls", "POST /api/v1/conversations/:id/messages", "PUT /api/v1/devices/push-token", "GET /api/v1/ws"]) {
      expect(names).toContain(must);
    }

    for (const { method, url } of routes) {
      if (PUBLIC.has(`${method} ${url}`)) continue;
      const variants: Record<string, string>[] = [{}, { authorization: "Bearer vca_not-a-real-token-000000" }, { authorization: "Basic abc" }];
      for (const headers of variants) {
        const r = await send(method, url, headers);
        expect({ route: `${method} ${url}`, status: r.statusCode }).toEqual({ route: `${method} ${url}`, status: 401 });
      }
    }
  });

  it("a session that must change its password can use nothing else", async () => {
    const { temporaryPassword } = await auth.createUser("alice", { type: "system", id: null });
    const login = await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username: "alice", password: temporaryPassword } });
    const token = login.json().accessToken as string;
    for (const { method, url } of routes) {
      const name = `${method} ${url}`;
      if (PUBLIC.has(name) || RESTRICTED_OK.has(name)) continue;
      const r = await send(method, url, { authorization: `Bearer ${token}` });
      expect({ route: name, status: r.statusCode, code: r.json().error?.code }).toEqual({ route: name, status: 403, code: "PASSWORD_CHANGE_REQUIRED" });
    }
  });

  it("a full run of the app's flows leaves no password, token or message text in the logs", async () => {
    const secrets: string[] = [LIVEKIT_TOKEN];
    const tokens: Record<string, string> = {};
    for (const name of ["alice", "bob"]) {
      const { temporaryPassword } = await auth.createUser(name, { type: "system", id: null });
      const newPassword = `${name} brand new passphrase 77`;
      const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username: name, password: temporaryPassword } })).json();
      const changed = (
        await app.inject({
          method: "POST",
          url: "/api/v1/auth/change-password",
          headers: { authorization: `Bearer ${first.accessToken}` },
          payload: { currentPassword: temporaryPassword, newPassword },
        })
      ).json();
      // A wrong password and a refresh, too.
      await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username: name, password: "wrong password guess 1" } });
      const refreshed = (await app.inject({ method: "POST", url: "/api/v1/auth/refresh", payload: { refreshToken: changed.refreshToken } })).json();
      tokens[name] = refreshed.accessToken;
      secrets.push(temporaryPassword, newPassword, "wrong password guess 1", first.accessToken, first.refreshToken, changed.accessToken, changed.refreshToken, refreshed.accessToken, refreshed.refreshToken);
    }
    const as = (who: string, method: "GET" | "POST" | "PUT", url: string, payload?: object) =>
      app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${tokens[who]}` }, ...(payload ? { payload } : {}) });

    const fcm = "fcm-device-token-SENTINEL-0123456789";
    await as("bob", "PUT", "/devices/push-token", { fcmToken: fcm });
    const qr = (await as("alice", "POST", "/connection-qr", {})).json();
    const req = (await as("bob", "POST", "/connection-requests", { token: qr.token })).json();
    const conn = (await as("alice", "POST", `/connection-requests/${req.id}/accept`, {})).json();
    await as("bob", "POST", "/connection-requests", { token: qr.token }); // reuse: refused
    const call = (await as("alice", "POST", "/calls", { connectionId: conn.id, kind: "video" })).json();
    await as("alice", "POST", `/calls/${call.callId}/cancel`, {});
    const text = "message body SENTINEL that must stay private";
    await as("alice", "POST", `/conversations/${conn.conversationId}/messages`, { clientMessageId: randomUUID(), epoch: 0, text });
    await as("bob", "GET", `/conversations/${conn.conversationId}/messages`);
    await as("alice", "POST", `/conversations/${conn.conversationId}/messages`, { clientMessageId: randomUUID(), epoch: 0, text: `${text} ${"x".repeat(4000)}` });
    secrets.push(fcm, qr.token, qr.qrPayload, text);

    const all = logs.join("\n");
    expect(logs.length).toBeGreaterThan(20);
    for (const s of secrets) expect({ leaked: all.includes(s) ? s : null }).toEqual({ leaked: null });
    expect(all).not.toContain("SENTINEL");
  });
});
