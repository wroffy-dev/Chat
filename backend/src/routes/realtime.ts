import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import { AppError } from "../errors.js";
import type { RealtimeHub } from "../realtime/hub.js";

/** Close codes (docs/14-api-contracts.md §10). */
export const WS_CLOSE = { tokenExpired: 4401, sessionEnded: 4403, policy: 1008 } as const;

export interface RealtimeOptions {
  auth: AuthService;
  hub: RealtimeHub;
  /** How often an open socket re-checks its session (revocation, logout, disabled account). */
  recheckMs?: number;
  pingMs?: number;
}

/**
 * GET /api/v1/ws — one socket per signed-in app, authenticated by the Bearer header on the upgrade
 * request (never a token in the URL). Server → app only: "something changed" signals with IDs.
 * The app sends nothing but WebSocket pongs; anything else is ignored, and floods close the socket.
 */
export async function realtimeRoutes(app: FastifyInstance, opts: RealtimeOptions) {
  const { auth, hub } = opts;
  const recheckMs = opts.recheckMs ?? 60_000;
  const pingMs = opts.pingMs ?? 25_000;

  app.get("/ws", { websocket: true, onRequest: requireUser(auth), config: { rateLimit: { max: 30, timeWindow: 60_000 } } }, (socket, request) => {
    const { user, session } = authOf(request);
    const token = (request.headers.authorization ?? "").slice("Bearer ".length);
    hub.add(user.id, socket);

    // Dead connections (phone asleep, network gone) are dropped when a ping goes unanswered.
    let alive = true;
    socket.on("pong", () => {
      alive = true;
    });
    const ping = setInterval(() => {
      if (!alive) return socket.terminate();
      alive = false;
      socket.ping();
    }, pingMs);

    // The access token's lifetime bounds the socket; the app reconnects with a fresh one.
    const expiry = setTimeout(() => socket.close(WS_CLOSE.tokenExpired, "token expired"), Math.max(0, session.accessExpiresAt.getTime() - Date.now()));
    const recheck = setInterval(() => {
      auth.authenticate(token).catch((err: unknown) => {
        const expired = err instanceof AppError && err.code === "TOKEN_EXPIRED";
        socket.close(expired ? WS_CLOSE.tokenExpired : WS_CLOSE.sessionEnded, expired ? "token expired" : "session ended");
      });
    }, recheckMs);

    let windowStart = Date.now();
    let frames = 0;
    socket.on("message", () => {
      const now = Date.now();
      if (now - windowStart >= 1000) {
        windowStart = now;
        frames = 0;
      }
      if (++frames > 20) socket.close(WS_CLOSE.policy, "too many messages");
    });

    socket.on("close", () => {
      clearInterval(ping);
      clearInterval(recheck);
      clearTimeout(expiry);
      hub.remove(user.id, socket);
    });
    socket.send(JSON.stringify({ type: "ready", data: {} }));
  });
}
