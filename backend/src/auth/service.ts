import type { PrismaClient, Prisma, Session, User } from "../generated/prisma/client.js";
import { AppError, appError } from "../errors.js";
import { audit } from "./audit.js";
import {
  checkPasswordStrength,
  generateTemporaryPassword,
  PasswordHasher,
  USER_PASSWORD_MIN,
} from "./passwords.js";
import { generateToken, hashToken } from "./tokens.js";

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

/** Session policy (docs/04-accounts-and-authentication.md §4). */
export const SESSION_POLICY = {
  accessTtlMs: 15 * MINUTE,
  idleTtlMs: 30 * DAY,
  absoluteTtlMs: 90 * DAY,
  /** last_used_at / last_seen_at are written at most this often. */
  touchIntervalMs: MINUTE,
};

/** Account login backoff: free attempts, then 1, 2, 4 … minutes, capped (docs/04 §5). */
export const THROTTLE_POLICY = { freeAttempts: 5, baseDelayMs: MINUTE, maxDelayMs: 30 * MINUTE };

export function throttleDelayMs(failedCount: number): number {
  if (failedCount < THROTTLE_POLICY.freeAttempts) return 0;
  const exp = failedCount - THROTTLE_POLICY.freeAttempts;
  return Math.min(THROTTLE_POLICY.baseDelayMs * 2 ** Math.min(exp, 20), THROTTLE_POLICY.maxDelayMs);
}

/** Most app users that can exist (decision D-21; the database CHECK on users.slot agrees). */
export const MAX_USERS = 10;

export interface TokenPair {
  accessToken: string;
  accessExpiresAt: Date;
  refreshToken: string;
}

export interface LoginResult extends TokenPair {
  user: { id: string; username: string; mustChangePassword: boolean };
}

export interface AuthContext {
  user: User;
  session: Session;
}

export interface RequestMeta {
  ip: string | null;
  deviceModel?: string | undefined;
  appVersionCode?: number | undefined;
}

export type RevokeReason =
  | "logout"
  | "new_login"
  | "password_change"
  | "admin_reset"
  | "admin_revoke"
  | "refresh_reuse"
  | "disabled"
  | "expired";

export function normalizeUsername(raw: string): string {
  return raw.trim().toLowerCase();
}

export const USERNAME_PATTERN = /^[a-z0-9._-]{3,32}$/;

/**
 * Small bounded in-memory backoff for usernames that do not exist, so throttling behaves the same
 * whether or not an account exists. Existing accounts use the persisted counters on `users`.
 */
class UnknownUserThrottle {
  private readonly failures = new Map<string, { count: number; until: number }>();
  constructor(private readonly maxEntries = 1000) {}

  blockedUntil(username: string, now: number): number | null {
    const e = this.failures.get(username);
    return e && e.until > now ? e.until : null;
  }

  recordFailure(username: string, now: number) {
    const e = this.failures.get(username) ?? { count: 0, until: 0 };
    e.count += 1;
    e.until = now + throttleDelayMs(e.count);
    this.failures.delete(username);
    this.failures.set(username, e);
    if (this.failures.size > this.maxEntries) {
      const oldest = this.failures.keys().next().value;
      if (oldest !== undefined) this.failures.delete(oldest);
    }
  }
}

export class AuthService {
  private readonly unknownThrottle = new UnknownUserThrottle();

  constructor(
    private readonly prisma: PrismaClient,
    private readonly hasher: PasswordHasher,
    private readonly now: () => Date = () => new Date(),
  ) {}

  // ---------------------------------------------------------------------------------------------
  // App user authentication
  // ---------------------------------------------------------------------------------------------

  async login(rawUsername: string, password: string, meta: RequestMeta): Promise<LoginResult> {
    const username = normalizeUsername(rawUsername);
    const now = this.now();
    const user = await this.prisma.user.findUnique({ where: { username } });

    if (!user) {
      const blocked = this.unknownThrottle.blockedUntil(username, now.getTime());
      if (blocked) throw this.throttled(blocked - now.getTime());
      await this.hasher.verifyDummy(password);
      this.unknownThrottle.recordFailure(username, now.getTime());
      await audit(this.prisma, { actorType: "system", action: "auth.login_failed", ip: meta.ip, metadata: { reason: "unknown_user" } });
      throw appError(401, "INVALID_CREDENTIALS");
    }

    if (user.throttledUntil && user.throttledUntil > now) {
      await audit(this.prisma, { actorType: "system", action: "auth.account_throttled", targetType: "user", targetId: user.id, ip: meta.ip });
      throw this.throttled(user.throttledUntil.getTime() - now.getTime());
    }

    if (!(await this.hasher.verify(user.passwordHash, password))) {
      const failed = user.failedLoginCount + 1;
      const delay = throttleDelayMs(failed);
      await this.prisma.user.update({
        where: { id: user.id },
        data: { failedLoginCount: failed, throttledUntil: delay > 0 ? new Date(now.getTime() + delay) : null },
      });
      await audit(this.prisma, { actorType: "system", action: "auth.login_failed", targetType: "user", targetId: user.id, ip: meta.ip, metadata: { reason: "wrong_password", failedCount: failed } });
      throw appError(401, "INVALID_CREDENTIALS");
    }

    // Only reveal the disabled state to someone who knows the password.
    if (user.status !== "active") {
      await audit(this.prisma, { actorType: "user", actorId: user.id, action: "auth.login_refused_disabled", ip: meta.ip });
      throw appError(403, "ACCOUNT_DISABLED");
    }

    const rehash = this.hasher.needsRehash(user.passwordHash) ? await this.hasher.hash(password) : null;
    const tokens = this.newTokens(now);

    await this.prisma.$transaction(async (tx) => {
      await tx.user.update({
        where: { id: user.id },
        data: { failedLoginCount: 0, throttledUntil: null, lastSeenAt: now, ...(rehash ? { passwordHash: rehash } : {}) },
      });
      // One active session per user (decision D-04, proposed): a new login ends the others.
      await this.revokeUserSessionsTx(tx, user.id, "new_login", now);
      await tx.session.create({
        data: {
          userId: user.id,
          ...this.tokenColumns(tokens, now),
          restricted: user.mustChangePassword,
          deviceModel: meta.deviceModel ?? null,
          appVersionCode: meta.appVersionCode ?? null,
          createdAt: now,
          lastUsedAt: now,
        },
      });
      await audit(tx, { actorType: "user", actorId: user.id, action: "auth.login_succeeded", ip: meta.ip });
    });

    return {
      ...tokens,
      user: { id: user.id, username: user.username, mustChangePassword: user.mustChangePassword },
    };
  }

  /** Resolves an access token to its session and user, or throws the matching 401/403. */
  async authenticate(accessToken: string): Promise<AuthContext> {
    const now = this.now();
    const session = await this.prisma.session.findUnique({
      where: { accessTokenHash: hashToken(accessToken) },
      include: { user: true },
    });
    if (!session || session.revokedAt) throw appError(401, "SESSION_REVOKED");
    if (session.idleExpiresAt <= now || session.absoluteExpiresAt <= now) {
      await this.revokeSession(session.id, "expired", now);
      throw appError(401, "SESSION_REVOKED");
    }
    if (session.accessExpiresAt <= now) throw appError(401, "TOKEN_EXPIRED");
    if (session.user.status !== "active") {
      await this.revokeUserSessions(session.userId, "disabled");
      throw appError(403, "ACCOUNT_DISABLED");
    }

    if (now.getTime() - session.lastUsedAt.getTime() >= SESSION_POLICY.touchIntervalMs) {
      await this.prisma.$transaction([
        this.prisma.session.update({ where: { id: session.id }, data: { lastUsedAt: now } }),
        this.prisma.user.update({ where: { id: session.userId }, data: { lastSeenAt: now } }),
      ]);
    }
    const { user, ...rest } = session;
    return { user, session: rest };
  }

  async refresh(refreshToken: string, meta: RequestMeta): Promise<LoginResult> {
    const now = this.now();
    const hash = hashToken(refreshToken);
    const session = await this.prisma.session.findUnique({ where: { refreshTokenHash: hash }, include: { user: true } });

    if (!session) {
      // A previously rotated token presented again means it was copied: end that session.
      const reused = await this.prisma.session.findFirst({ where: { previousRefreshTokenHash: hash, revokedAt: null } });
      if (reused) {
        await this.revokeSession(reused.id, "refresh_reuse", now);
        await audit(this.prisma, { actorType: "system", action: "auth.refresh_reuse_detected", targetType: "user", targetId: reused.userId, ip: meta.ip });
      }
      throw appError(401, "SESSION_REVOKED");
    }
    if (session.revokedAt) throw appError(401, "SESSION_REVOKED");
    if (session.idleExpiresAt <= now || session.absoluteExpiresAt <= now) {
      await this.revokeSession(session.id, "expired", now);
      throw appError(401, "SESSION_REVOKED");
    }
    if (session.user.status !== "active") {
      await this.revokeUserSessions(session.userId, "disabled");
      throw appError(403, "ACCOUNT_DISABLED");
    }

    const tokens = this.newTokens(now);
    // Compare-and-swap on the old hash: of two concurrent refreshes with one token, only one wins.
    const updated = await this.prisma.session.updateMany({
      where: { id: session.id, refreshTokenHash: hash, revokedAt: null },
      data: {
        ...this.tokenColumns(tokens, now, session.absoluteExpiresAt),
        previousRefreshTokenHash: hash,
        lastUsedAt: now,
        ...(meta.appVersionCode !== undefined ? { appVersionCode: meta.appVersionCode } : {}),
      },
    });
    if (updated.count !== 1) throw appError(401, "SESSION_REVOKED");

    return {
      ...tokens,
      user: { id: session.user.id, username: session.user.username, mustChangePassword: session.user.mustChangePassword },
    };
  }

  async logout(ctx: AuthContext, meta: RequestMeta): Promise<void> {
    await this.revokeSession(ctx.session.id, "logout", this.now());
    await audit(this.prisma, { actorType: "user", actorId: ctx.user.id, action: "auth.logout", ip: meta.ip });
  }

  /** Changes the caller's password; ends all their other sessions and rotates the current one. */
  async changePassword(ctx: AuthContext, currentPassword: string, newPassword: string, meta: RequestMeta): Promise<LoginResult> {
    if (!(await this.hasher.verify(ctx.user.passwordHash, currentPassword))) {
      await audit(this.prisma, { actorType: "user", actorId: ctx.user.id, action: "user.password_change_failed", ip: meta.ip });
      throw appError(401, "INVALID_CREDENTIALS");
    }
    const problem = checkPasswordStrength(newPassword, USER_PASSWORD_MIN);
    if (problem || newPassword === currentPassword) {
      throw appError(422, "WEAK_PASSWORD", [{ path: "body/newPassword", rule: problem ?? "same_as_current" }]);
    }

    const now = this.now();
    const hash = await this.hasher.hash(newPassword);
    const tokens = this.newTokens(now);
    await this.prisma.$transaction(async (tx) => {
      await tx.user.update({
        where: { id: ctx.user.id },
        data: { passwordHash: hash, passwordChangedAt: now, mustChangePassword: false },
      });
      await tx.session.updateMany({
        where: { userId: ctx.user.id, revokedAt: null, id: { not: ctx.session.id } },
        data: { revokedAt: now, revokeReason: "password_change", fcmToken: null },
      });
      await tx.session.update({
        where: { id: ctx.session.id },
        data: { ...this.tokenColumns(tokens, now, ctx.session.absoluteExpiresAt), restricted: false, previousRefreshTokenHash: null },
      });
      await audit(tx, { actorType: "user", actorId: ctx.user.id, action: "user.password_changed", ip: meta.ip });
    });

    return { ...tokens, user: { id: ctx.user.id, username: ctx.user.username, mustChangePassword: false } };
  }

  // ---------------------------------------------------------------------------------------------
  // Administration (called from the Admin Console and the server CLI)
  // ---------------------------------------------------------------------------------------------

  /** Creates an app user (at most MAX_USERS) with a temporary password that must be changed at first login. */
  async createUser(rawUsername: string, actor: { type: "admin" | "system"; id: string | null }): Promise<{ user: User; temporaryPassword: string }> {
    const username = normalizeUsername(rawUsername);
    if (!USERNAME_PATTERN.test(username)) {
      throw appError(400, "VALIDATION_FAILED", [{ path: "body/username", rule: "pattern" }]);
    }
    const temporaryPassword = generateTemporaryPassword();
    const passwordHash = await this.hasher.hash(temporaryPassword);
    const now = this.now();

    const user = await this.prisma.$transaction(async (tx) => {
      const existing = await tx.user.findMany({ select: { slot: true, username: true } });
      if (existing.some((u) => u.username === username)) throw new AppError(409, "CONFLICT", "That username is already taken.");
      const free = Array.from({ length: MAX_USERS }, (_, i) => i + 1).find((slot) => !existing.some((u) => u.slot === slot));
      if (free === undefined) throw new AppError(409, "CONFLICT", `All ${MAX_USERS} user accounts already exist.`);
      const created = await tx.user.create({
        data: { slot: free, username, passwordHash, passwordChangedAt: now, mustChangePassword: true },
      });
      await audit(tx, { actorType: actor.type, actorId: actor.id, action: "admin.user_created", targetType: "user", targetId: created.id });
      return created;
    });
    return { user, temporaryPassword };
  }

  /** Sets a new temporary password and ends every session of that user. */
  async resetPassword(userId: string, actor: { type: "admin" | "system"; id: string | null }): Promise<string> {
    const temporaryPassword = generateTemporaryPassword();
    const passwordHash = await this.hasher.hash(temporaryPassword);
    const now = this.now();
    await this.prisma.$transaction(async (tx) => {
      const user = await tx.user.findUnique({ where: { id: userId } });
      if (!user) throw appError(404, "NOT_FOUND");
      await tx.user.update({
        where: { id: userId },
        data: { passwordHash, passwordChangedAt: now, mustChangePassword: true, failedLoginCount: 0, throttledUntil: null },
      });
      await this.revokeUserSessionsTx(tx, userId, "admin_reset", now);
      await audit(tx, { actorType: actor.type, actorId: actor.id, action: "admin.user_password_reset", targetType: "user", targetId: userId });
    });
    return temporaryPassword;
  }

  async setUserStatus(userId: string, status: "active" | "disabled", actor: { type: "admin" | "system"; id: string | null }) {
    const now = this.now();
    await this.prisma.$transaction(async (tx) => {
      const user = await tx.user.findUnique({ where: { id: userId } });
      if (!user) throw appError(404, "NOT_FOUND");
      await tx.user.update({ where: { id: userId }, data: { status } });
      if (status === "disabled") await this.revokeUserSessionsTx(tx, userId, "disabled", now);
      await audit(tx, {
        actorType: actor.type,
        actorId: actor.id,
        action: status === "disabled" ? "admin.user_disabled" : "admin.user_enabled",
        targetType: "user",
        targetId: userId,
      });
    });
  }

  async revokeUserSessions(userId: string, reason: RevokeReason, actor?: { type: "admin" | "system"; id: string | null }) {
    const now = this.now();
    await this.prisma.$transaction(async (tx) => {
      await this.revokeUserSessionsTx(tx, userId, reason, now);
      if (actor) {
        await audit(tx, { actorType: actor.type, actorId: actor.id, action: "admin.user_sessions_revoked", targetType: "user", targetId: userId });
      }
    });
  }

  async revokeSession(sessionId: string, reason: RevokeReason, now = this.now()) {
    await this.prisma.session.updateMany({
      where: { id: sessionId, revokedAt: null },
      data: { revokedAt: now, revokeReason: reason, fcmToken: null },
    });
  }

  // ---------------------------------------------------------------------------------------------

  private async revokeUserSessionsTx(tx: Prisma.TransactionClient, userId: string, reason: RevokeReason, now: Date) {
    await tx.session.updateMany({
      where: { userId, revokedAt: null },
      data: { revokedAt: now, revokeReason: reason, fcmToken: null },
    });
  }

  private newTokens(now: Date): TokenPair {
    return {
      accessToken: generateToken("vca_"),
      accessExpiresAt: new Date(now.getTime() + SESSION_POLICY.accessTtlMs),
      refreshToken: generateToken("vcr_"),
    };
  }

  private tokenColumns(tokens: TokenPair, now: Date, absoluteExpiresAt?: Date) {
    return {
      accessTokenHash: hashToken(tokens.accessToken),
      accessExpiresAt: tokens.accessExpiresAt,
      refreshTokenHash: hashToken(tokens.refreshToken),
      idleExpiresAt: new Date(now.getTime() + SESSION_POLICY.idleTtlMs),
      absoluteExpiresAt: absoluteExpiresAt ?? new Date(now.getTime() + SESSION_POLICY.absoluteTtlMs),
    };
  }

  private throttled(remainingMs: number): AppError {
    return new AppError(429, "RATE_LIMITED", "Too many attempts. Try again later.", undefined, Math.max(1, Math.ceil(remainingMs / 1000)));
  }
}
