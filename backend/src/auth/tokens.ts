import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

/** 256-bit random token, base64url, with a short prefix that makes leaked tokens recognisable. */
export function generateToken(prefix: "vca_" | "vcr_" | "vcadm_"): string {
  return prefix + randomBytes(32).toString("base64url");
}

/** Tokens are stored only as SHA-256 hashes (docs/04-accounts-and-authentication.md §4). */
export function hashToken(token: string): Uint8Array<ArrayBuffer> {
  return Uint8Array.from(createHash("sha256").update(token, "utf8").digest());
}

export function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

/** Truncates an IP for audit storage: IPv4 to /24, IPv6 to /48 (docs/05-admin-console.md §6). */
export function truncateIp(ip: string | undefined): string | null {
  if (!ip) return null;
  const v4 = ip.startsWith("::ffff:") ? ip.slice(7) : ip;
  if (/^\d+\.\d+\.\d+\.\d+$/.test(v4)) {
    const [a, b, c] = v4.split(".");
    return `${a}.${b}.${c}.0`;
  }
  if (ip.includes(":")) {
    const groups = ip.split("::")[0]!.split(":").filter(Boolean);
    while (groups.length < 3) groups.push("0");
    return `${groups.slice(0, 3).join(":")}::`;
  }
  return null;
}
