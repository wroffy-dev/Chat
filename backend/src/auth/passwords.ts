import argon2 from "argon2";
import { randomInt } from "node:crypto";

export interface HashParams {
  memoryCost: number;
  timeCost: number;
}

export const USER_PASSWORD_MIN = 12;
export const ADMIN_PASSWORD_MIN = 16;
export const PASSWORD_MAX = 128;

// Small local deny-list of very common passwords that meet the length rule.
const COMMON = new Set([
  "password1234", "123456789012", "qwertyuiopas", "passwordpassword", "iloveyou1234",
  "aaaaaaaaaaaa", "111111111111", "000000000000", "letmein12345", "welcome12345",
  "administrator", "adminadmin123", "changeme1234", "qwerty123456", "1234567890ab",
]);

export type PasswordProblem = "too_short" | "too_long" | "too_common";

export function checkPasswordStrength(password: string, minLength: number): PasswordProblem | null {
  const length = [...password].length;
  if (length < minLength) return "too_short";
  if (length > PASSWORD_MAX) return "too_long";
  if (COMMON.has(password.toLowerCase())) return "too_common";
  if (new Set(password).size <= 2) return "too_common";
  return null;
}

export class PasswordHasher {
  private dummyHash: Promise<string> | undefined;

  constructor(private readonly params: HashParams) {}

  hash(password: string): Promise<string> {
    return argon2.hash(password, {
      type: argon2.argon2id,
      memoryCost: this.params.memoryCost,
      timeCost: this.params.timeCost,
      parallelism: 1,
    });
  }

  async verify(hash: string, password: string): Promise<boolean> {
    try {
      return await argon2.verify(hash, password);
    } catch {
      return false;
    }
  }

  /** True when the stored hash was made with weaker parameters than the current ones. */
  needsRehash(hash: string): boolean {
    return argon2.needsRehash(hash, {
      memoryCost: this.params.memoryCost,
      timeCost: this.params.timeCost,
      parallelism: 1,
    });
  }

  /**
   * Spends the same time as a real verification so response timing does not reveal whether a
   * username exists (docs/04-accounts-and-authentication.md §3).
   */
  async verifyDummy(password: string): Promise<void> {
    this.dummyHash ??= this.hash(generateTemporaryPassword());
    await this.verify(await this.dummyHash, password);
  }
}

// No look-alike characters (0/O, 1/l/I) so a password read out in person is not mistyped.
const ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

/** 18 characters from a 56-symbol alphabet ≈ 104 bits. Shown once, never stored in plaintext. */
export function generateTemporaryPassword(length = 18): string {
  let out = "";
  for (let i = 0; i < length; i++) out += ALPHABET[randomInt(ALPHABET.length)];
  return out;
}
