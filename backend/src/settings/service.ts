import type { PrismaClient } from "../generated/prisma/client.js";
import { audit } from "../auth/audit.js";
import { AppError } from "../errors.js";

/**
 * Admin-editable settings (docs/05-admin-console.md §5). Only these keys exist; anything else is
 * refused. The message lifetime may be shortened but never raised above the 24 h of decision D-02.
 */
export const SETTING_DEFS = {
  "call.max_video_quality": { kind: "enum", values: ["1080p", "720p", "540p", "360p"], label: "Highest video quality offered to the apps" },
  "call.ring_timeout_seconds": { kind: "int", min: 20, max: 90, label: "How long an incoming call rings (seconds)" },
  "chat.unread_backstop_hours": { kind: "int", min: 1, max: 24, label: "Longest a message can exist (hours; D-02 caps it at 24)" },
  "push.enabled": { kind: "bool", label: "Send push notifications (emergency off switch)" },
} as const;

export type SettingKey = keyof typeof SETTING_DEFS;
export interface Settings {
  "call.max_video_quality": "1080p" | "720p" | "540p" | "360p";
  "call.ring_timeout_seconds": number;
  "chat.unread_backstop_hours": number;
  "push.enabled": boolean;
}

export const SETTING_KEYS = Object.keys(SETTING_DEFS) as SettingKey[];

export class SettingsService {
  private cache: { at: number; values: Settings } | null = null;

  constructor(
    private readonly prisma: PrismaClient,
    private readonly defaults: Settings,
    /** Values are re-read at most this often; the admin's own change applies at once. */
    private readonly cacheMs = 10_000,
  ) {}

  async all(): Promise<Settings> {
    if (this.cache && Date.now() - this.cache.at < this.cacheMs) return this.cache.values;
    const rows = await this.prisma.appSetting.findMany();
    const values: Settings = { ...this.defaults };
    for (const row of rows) {
      const key = row.key as SettingKey;
      // A stored value that no longer validates falls back to the default instead of breaking calls.
      if (key in SETTING_DEFS && validate(key, row.value) !== undefined) (values as unknown as Record<string, unknown>)[key] = row.value;
    }
    this.cache = { at: Date.now(), values };
    return values;
  }

  async get<K extends SettingKey>(key: K): Promise<Settings[K]> {
    return (await this.all())[key];
  }

  /** Sets one value from the Admin Console form; validated, audited (old → new). */
  async set(key: string, raw: string, adminId: string): Promise<void> {
    if (!(key in SETTING_DEFS)) throw new AppError(400, "VALIDATION_FAILED", "Unknown setting.");
    const k = key as SettingKey;
    const value = validate(k, parseRaw(k, raw));
    if (value === undefined) throw new AppError(400, "VALIDATION_FAILED", "That value is not allowed for this setting.");
    const before = await this.get(k);
    await this.prisma.$transaction(async (tx) => {
      await tx.appSetting.upsert({
        where: { key: k },
        create: { key: k, value, updatedBy: adminId },
        update: { value, updatedBy: adminId, updatedAt: new Date() },
      });
      await audit(tx, { actorType: "admin", actorId: adminId, action: "admin.setting_changed", metadata: { key: k, from: before, to: value } });
    });
    this.cache = null;
  }
}

function parseRaw(key: SettingKey, raw: string): unknown {
  const def = SETTING_DEFS[key];
  if (def.kind === "int") return /^\d{1,4}$/.test(raw.trim()) ? Number(raw.trim()) : undefined;
  if (def.kind === "bool") return raw === "true" ? true : raw === "false" ? false : undefined;
  return raw;
}

function validate(key: SettingKey, v: unknown): string | number | boolean | undefined {
  const def = SETTING_DEFS[key];
  switch (def.kind) {
    case "enum":
      return typeof v === "string" && (def.values as readonly string[]).includes(v) ? v : undefined;
    case "int":
      return typeof v === "number" && Number.isInteger(v) && v >= def.min && v <= def.max ? v : undefined;
    case "bool":
      return typeof v === "boolean" ? v : undefined;
  }
}
