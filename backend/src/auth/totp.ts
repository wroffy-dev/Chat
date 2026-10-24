import { createCipheriv, createDecipheriv, createHmac, randomBytes } from "node:crypto";
import { safeEqual } from "./tokens.js";

// RFC 6238 TOTP (SHA-1, 30 s, 6 digits) — the parameters every authenticator app supports.
const STEP_SECONDS = 30;
const DIGITS = 6;
const B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function base32Encode(buf: Buffer): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(s: string): Buffer {
  const clean = s.replace(/=+$/, "").toUpperCase();
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const ch of clean) {
    const idx = B32.indexOf(ch);
    if (idx < 0) throw new Error("invalid base32");
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

export function generateTotpSecret(): Buffer {
  return randomBytes(20);
}

export function totpStep(nowMs: number): number {
  return Math.floor(nowMs / 1000 / STEP_SECONDS);
}

export function totpCode(secret: Buffer, step: number): string {
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(step));
  const mac = createHmac("sha1", secret).update(counter).digest();
  const offset = mac[mac.length - 1]! & 0x0f;
  const binary = (mac.readUInt32BE(offset) & 0x7fffffff) % 10 ** DIGITS;
  return binary.toString().padStart(DIGITS, "0");
}

/**
 * Accepts the current step ±1 (clock drift). Returns the matched step, or null. Steps at or before
 * `lastUsedStep` are refused so a code cannot be replayed.
 */
export function verifyTotp(secret: Buffer, code: string, nowMs: number, lastUsedStep: bigint | null): number | null {
  if (!/^\d{6}$/.test(code)) return null;
  const current = totpStep(nowMs);
  for (const step of [current - 1, current, current + 1]) {
    if (lastUsedStep !== null && BigInt(step) <= lastUsedStep) continue;
    if (safeEqual(totpCode(secret, step), code)) return step;
  }
  return null;
}

export function otpauthUri(secret: Buffer, account: string, issuer = "Vault Chat Admin"): string {
  const label = encodeURIComponent(`${issuer}:${account}`);
  return `otpauth://totp/${label}?secret=${base32Encode(secret)}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`;
}

/** AES-256-GCM. Layout: 12-byte IV ‖ 16-byte tag ‖ ciphertext. */
export function encryptSecret(plain: Buffer, keyBase64: string): Buffer {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", Buffer.from(keyBase64, "base64"), iv);
  const ct = Buffer.concat([cipher.update(plain), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), ct]);
}

export function decryptSecret(blob: Buffer, keyBase64: string): Buffer {
  const decipher = createDecipheriv("aes-256-gcm", Buffer.from(keyBase64, "base64"), blob.subarray(0, 12));
  decipher.setAuthTag(blob.subarray(12, 28));
  return Buffer.concat([decipher.update(blob.subarray(28)), decipher.final()]);
}
