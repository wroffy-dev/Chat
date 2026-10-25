import { readFileSync } from "node:fs";
import type { PrismaClient } from "../generated/prisma/client.js";

/**
 * Wake-up pushes (docs/10-notifications.md). Payloads are opaque data messages: a type and an id,
 * never usernames, message text, tokens or secrets.
 */
export type PushPayload = { t: "call"; id: string } | { t: "call_end"; id: string } | { t: "msg" };

/** Delivers one payload to one FCM token. Throws `UnregisteredTokenError` for dead tokens. */
export interface PushTransport {
  send(fcmToken: string, payload: PushPayload, highPriority: boolean, ttlSeconds: number): Promise<void>;
}

export class UnregisteredTokenError extends Error {}

export class PushService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly transport: PushTransport | null,
    private readonly log: (msg: string, extra?: Record<string, unknown>) => void = () => {},
  ) {}

  get enabled(): boolean {
    return this.transport !== null;
  }

  /** Stores the device's FCM token on the caller's current session (one token, one session). */
  async registerToken(sessionId: string, fcmToken: string): Promise<void> {
    await this.prisma.$transaction([
      // A token can belong to only one session: moving phones or re-login takes it over.
      this.prisma.session.updateMany({ where: { fcmToken, id: { not: sessionId } }, data: { fcmToken: null, fcmTokenUpdatedAt: new Date() } }),
      this.prisma.session.update({ where: { id: sessionId }, data: { fcmToken, fcmTokenUpdatedAt: new Date() } }),
    ]);
  }

  async clearToken(sessionId: string): Promise<void> {
    await this.prisma.session.update({ where: { id: sessionId }, data: { fcmToken: null, fcmTokenUpdatedAt: new Date() } });
  }

  /**
   * Best effort: a push that fails never fails the call itself. Ringing is not guaranteed
   * (docs/10-notifications.md §6), and the caller sees "No answer" after the ring timeout.
   */
  async notifyUser(userId: string, payload: PushPayload, opts: { highPriority: boolean; ttlSeconds: number }): Promise<void> {
    if (!this.transport) return;
    const sessions = await this.prisma.session.findMany({
      where: { userId, revokedAt: null, fcmToken: { not: null } },
      select: { id: true, fcmToken: true },
    });
    for (const s of sessions) {
      try {
        await this.transport.send(s.fcmToken!, payload, opts.highPriority, opts.ttlSeconds);
      } catch (err) {
        if (err instanceof UnregisteredTokenError) {
          await this.prisma.session.update({ where: { id: s.id }, data: { fcmToken: null } });
          this.log("push token unregistered; cleared", { type: payload.t });
        } else {
          // Never log the token or payload contents beyond the type.
          this.log("push send failed", { type: payload.t, error: err instanceof Error ? err.message : "unknown" });
        }
      }
    }
  }
}

/** Firebase Admin SDK transport (HTTP v1 API). Credentials come from a mounted service-account file. */
export async function createFcmTransport(credentialsFile: string): Promise<PushTransport> {
  const { initializeApp, cert } = await import("firebase-admin/app");
  const { getMessaging } = await import("firebase-admin/messaging");
  const serviceAccount = JSON.parse(readFileSync(credentialsFile, "utf8")) as Record<string, string>;
  const app = initializeApp({ credential: cert(serviceAccount) }, "vaultchat-push");
  const messaging = getMessaging(app);
  return {
    async send(fcmToken, payload, highPriority, ttlSeconds) {
      try {
        await messaging.send({
          token: fcmToken,
          data: { ...payload },
          android: { priority: highPriority ? "high" : "normal", ttl: ttlSeconds * 1000 },
        });
      } catch (err) {
        const code = (err as { code?: string }).code ?? "";
        if (code === "messaging/registration-token-not-registered" || code === "messaging/invalid-registration-token") {
          throw new UnregisteredTokenError(code);
        }
        throw err;
      }
    },
  };
}
