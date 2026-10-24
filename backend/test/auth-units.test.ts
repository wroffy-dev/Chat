import { describe, expect, it } from "vitest";
import { checkPasswordStrength, generateTemporaryPassword } from "../src/auth/passwords.js";
import { throttleDelayMs } from "../src/auth/service.js";
import { truncateIp } from "../src/auth/tokens.js";
import { base32Decode, base32Encode, decryptSecret, encryptSecret, totpCode, verifyTotp } from "../src/auth/totp.js";

describe("TOTP (RFC 6238)", () => {
  // RFC 6238 Appendix B, SHA-1 secret "12345678901234567890"; 6-digit codes are the last 6 digits.
  const secret = Buffer.from("12345678901234567890");
  it.each([
    [59, "287082"],
    [1111111109, "081804"],
    [1234567890, "005924"],
    [2000000000, "279037"],
  ])("t=%i → %s", (t, code) => {
    expect(totpCode(secret, Math.floor(t / 30))).toBe(code);
  });

  it("accepts ±1 step and refuses replays", () => {
    const now = 1_800_000_000_000;
    const step = Math.floor(now / 30_000);
    expect(verifyTotp(secret, totpCode(secret, step - 1), now, null)).toBe(step - 1);
    expect(verifyTotp(secret, totpCode(secret, step + 2), now, null)).toBeNull();
    expect(verifyTotp(secret, totpCode(secret, step), now, BigInt(step))).toBeNull();
    expect(verifyTotp(secret, "12345", now, null)).toBeNull();
  });

  it("round-trips base32 and encryption", () => {
    const raw = Buffer.from("hello totp secret!!!");
    expect(base32Decode(base32Encode(raw))).toEqual(raw);
    const key = Buffer.alloc(32, 7).toString("base64");
    const blob = encryptSecret(raw, key);
    expect(decryptSecret(blob, key)).toEqual(raw);
    blob[blob.length - 1]! ^= 1;
    expect(() => decryptSecret(blob, key)).toThrow();
  });
});

describe("password rules", () => {
  it("enforces length and rejects common passwords", () => {
    expect(checkPasswordStrength("short", 12)).toBe("too_short");
    expect(checkPasswordStrength("x".repeat(129) + "yz", 12)).toBe("too_long");
    expect(checkPasswordStrength("Password1234", 12)).toBe("too_common");
    expect(checkPasswordStrength("aaaaaaaaaaaaaaaa", 12)).toBe("too_common");
    expect(checkPasswordStrength("correct horse battery", 12)).toBeNull();
  });

  it("generates unambiguous temporary passwords", () => {
    const pw = generateTemporaryPassword();
    expect(pw).toHaveLength(18);
    expect(pw).not.toMatch(/[01lIO]/);
    expect(checkPasswordStrength(pw, 12)).toBeNull();
  });
});

describe("throttle and IP truncation", () => {
  it("backs off exponentially after five failures, capped at 30 minutes", () => {
    expect(throttleDelayMs(4)).toBe(0);
    expect(throttleDelayMs(5)).toBe(60_000);
    expect(throttleDelayMs(6)).toBe(120_000);
    expect(throttleDelayMs(50)).toBe(30 * 60_000);
  });

  it("truncates IPv4 to /24 and IPv6 to /48", () => {
    expect(truncateIp("203.0.113.77")).toBe("203.0.113.0");
    expect(truncateIp("::ffff:198.51.100.9")).toBe("198.51.100.0");
    expect(truncateIp("2001:db8:abcd:12::1")).toBe("2001:db8:abcd::");
    expect(truncateIp(undefined)).toBeNull();
  });
});
