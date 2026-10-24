import { randomBytes } from "node:crypto";
import type { AdminAccount, AdminSession, PrismaClient } from "../generated/prisma/client.js";
import { audit } from "../auth/audit.js";
import { ADMIN_PASSWORD_MIN, checkPasswordStrength, type PasswordHasher } from "../auth/passwords.js";
import { normalizeUsername, throttleDelayMs, USERNAME_PATTERN } from "../auth/service.js";
import { generateToken, hashToken } from "../auth/tokens.js";
import { decryptSecret, encryptSecret, verifyTotp } from "../auth/totp.js";

const MINUTE = 60_000;

/** Admin session policy (docs/04-accounts-and-authentication.md §8). */
export const ADMIN_SESSION_POLICY = { idleTtlMs: 15 * MINUTE, absoluteTtlMs: 8 * 60 * MINUTE };

export class AdminLoginError extends Error {
  constructor(public readonly reason: "invalid" | "throttled", public readonly retryAfterMs = 0) {
    super(reason);
  }
}

export class AdminSetupError extends Error {}

export interface AdminContext {
  admin: AdminAccount;
  session: AdminSession;
}

export class AdminService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly hasher: PasswordHasher,
    private readonly totpKeyBase64: string,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async isInitialised(): Promise<boolean> {
    return (await this.prisma.adminAccount.count()) > 0;
  }

  /** Validates inputs for admin creation/reset before the TOTP enrolment step. */
  validateCredentials(rawUsername: string, password: string): string {
    const username = normalizeUsername(rawUsername);
    if (!USERNAME_PATTERN.test(username)) throw new AdminSetupError("Username must be 3–32 characters: a-z, 0-9, dot, dash, underscore.");
    const problem = checkPasswordStrength(password, ADMIN_PASSWORD_MIN);
    if (problem) throw new AdminSetupError(`Password rejected (${problem}). Use at least ${ADMIN_PASSWORD_MIN} characters.`);
    return username;
  }

  /**
   * Creates the single administrator. Called only from the server CLI after the operator proved
   * TOTP enrolment by entering a valid code. There is no web-based admin registration.
   */
  async createAdmin(rawUsername: string, password: string, totpSecret: Buffer): Promise<AdminAccount> {
    const username = this.validateCredentials(rawUsername, password);
    if (await this.isInitialised()) throw new AdminSetupError("An administrator already exists. Use admin:reset instead.");
    const passwordHash = await this.hasher.hash(password);
    return this.prisma.$transaction(async (tx) => {
      const admin = await tx.adminAccount.create({
        data: { username, passwordHash, totpSecretEnc: Uint8Array.from(encryptSecret(totpSecret, this.totpKeyBase64)) },
      });
      await audit(tx, { actorType: "system", actorId: null, action: "admin.created", targetType: "admin", targetId: admin.id, metadata: { via: "cli" } });
      return admin;
    });
  }

  /** Recovery (lost password or authenticator): new password + TOTP secret, all admin sessions ended. */
  async resetAdmin(rawUsername: string, password: string, totpSecret: Buffer): Promise<AdminAccount> {
    const username = this.validateCredentials(rawUsername, password);
    const existing = await this.prisma.adminAccount.findFirst();
    if (!existing) throw new AdminSetupError("No administrator exists. Use admin:create.");
    const passwordHash = await this.hasher.hash(password);
    const now = this.now();
    return this.prisma.$transaction(async (tx) => {
      const admin = await tx.adminAccount.update({
        where: { id: existing.id },
        data: {
          username,
          passwordHash,
          totpSecretEnc: Uint8Array.from(encryptSecret(totpSecret, this.totpKeyBase64)),
          totpLastUsedStep: null,
          failedLoginCount: 0,
          throttledUntil: null,
        },
      });
      await tx.adminSession.updateMany({ where: { adminId: admin.id, revokedAt: null }, data: { revokedAt: now } });
      await audit(tx, { actorType: "system", action: "admin.recovered", targetType: "admin", targetId: admin.id, metadata: { via: "cli" } });
      return admin;
    });
  }

  async login(rawUsername: string, password: string, totpCode: string): Promise<{ token: string; csrfToken: string }> {
    const now = this.now();
    const admin = await this.prisma.adminAccount.findUnique({ where: { username: normalizeUsername(rawUsername) } });
    if (!admin) {
      await this.hasher.verifyDummy(password);
      await audit(this.prisma, { actorType: "system", action: "admin.login_failed", metadata: { reason: "unknown_user" } });
      throw new AdminLoginError("invalid");
    }
    if (admin.throttledUntil && admin.throttledUntil > now) {
      throw new AdminLoginError("throttled", admin.throttledUntil.getTime() - now.getTime());
    }

    const passwordOk = await this.hasher.verify(admin.passwordHash, password);
    const secret = decryptSecret(Buffer.from(admin.totpSecretEnc), this.totpKeyBase64);
    const step = passwordOk ? verifyTotp(secret, totpCode.trim(), now.getTime(), admin.totpLastUsedStep) : null;

    if (!passwordOk || step === null) {
      const failed = admin.failedLoginCount + 1;
      // Single admin: no risk of locking out a real user, so a firmer 15-minute pause after 5 failures.
      const delay = failed >= 5 ? Math.max(15 * MINUTE, throttleDelayMs(failed)) : 0;
      await this.prisma.adminAccount.update({
        where: { id: admin.id },
        data: { failedLoginCount: failed, throttledUntil: delay ? new Date(now.getTime() + delay) : null },
      });
      await audit(this.prisma, {
        actorType: "system",
        action: "admin.login_failed",
        targetType: "admin",
        targetId: admin.id,
        metadata: { reason: passwordOk ? "bad_totp" : "wrong_password", failedCount: failed },
      });
      throw new AdminLoginError("invalid");
    }

    const token = generateToken("vcadm_");
    const csrfToken = randomBytes(32).toString("base64url");
    await this.prisma.$transaction(async (tx) => {
      await tx.adminAccount.update({
        where: { id: admin.id },
        data: { failedLoginCount: 0, throttledUntil: null, totpLastUsedStep: BigInt(step) },
      });
      await tx.adminSession.create({
        data: {
          adminId: admin.id,
          tokenHash: hashToken(token),
          csrfToken,
          createdAt: now,
          lastUsedAt: now,
          idleExpiresAt: new Date(now.getTime() + ADMIN_SESSION_POLICY.idleTtlMs),
          absoluteExpiresAt: new Date(now.getTime() + ADMIN_SESSION_POLICY.absoluteTtlMs),
        },
      });
      await audit(tx, { actorType: "admin", actorId: admin.id, action: "admin.login_succeeded" });
    });
    return { token, csrfToken };
  }

  /** Returns the admin context for a session cookie, sliding the idle timeout; null if invalid. */
  async authenticate(token: string | undefined): Promise<AdminContext | null> {
    if (!token) return null;
    const now = this.now();
    const session = await this.prisma.adminSession.findUnique({ where: { tokenHash: hashToken(token) }, include: { admin: true } });
    if (!session || session.revokedAt || session.idleExpiresAt <= now || session.absoluteExpiresAt <= now) return null;
    const idleExpiresAt = new Date(Math.min(now.getTime() + ADMIN_SESSION_POLICY.idleTtlMs, session.absoluteExpiresAt.getTime()));
    await this.prisma.adminSession.update({ where: { id: session.id }, data: { lastUsedAt: now, idleExpiresAt } });
    const { admin, ...rest } = session;
    return { admin, session: rest };
  }

  async logout(ctx: AdminContext): Promise<void> {
    await this.prisma.adminSession.update({ where: { id: ctx.session.id }, data: { revokedAt: this.now() } });
    await audit(this.prisma, { actorType: "admin", actorId: ctx.admin.id, action: "admin.logout" });
  }
}
