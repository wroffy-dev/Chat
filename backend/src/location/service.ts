import { audit } from "../auth/audit.js";
import { CONSENTS } from "../consents/store.js";
import { appError } from "../errors.js";
import type { LocationShare, PrismaClient } from "../generated/prisma/client.js";

/**
 * Location sharing with the administrator (owner decision D-06, 2026-11-02; docs/11).
 *
 * The user agrees in the app (the explanation text is versioned by CONSENT_VERSION); the app then sends
 * its position each time it is opened. Only the latest point is kept, overwritten in place, and it is
 * cleared when the user turns sharing off. Coordinates never go into logs or audit metadata.
 */
/** The consent's current version, from backend/consents/location-sharing.json (its only source). */
export const LOCATION_CONSENT_VERSION = CONSENTS["location-sharing"].version;

export type LocationPrecision = "approximate" | "precise";

export interface LocationState {
  enabled: boolean;
  consentVersion: number | null;
  currentConsentVersion: number;
  precision: LocationPrecision | null;
  lastSentAt: string | null;
  lastViewedByAdminAt: string | null;
}

export interface LocationReport {
  lat: number;
  lng: number;
  accuracyM: number;
  capturedAt: string;
  precision: LocationPrecision;
}

/** A fix may be at most this old when sent (the app sends a fresh one on each open). */
const MAX_FIX_AGE_MS = 60 * 60_000;
/** Phone clocks drift; a fix may claim to be this far in the future. */
const MAX_FUTURE_MS = 5 * 60_000;

export class LocationService {
  constructor(
    private readonly prisma: PrismaClient,
    private readonly now: () => Date = () => new Date(),
  ) {}

  async state(userId: string): Promise<LocationState> {
    return toState(await this.prisma.locationShare.findUnique({ where: { userId } }));
  }

  /** Turns sharing on (with the current consent text) or off (and deletes the stored point). */
  async setSharing(
    userId: string,
    wish: { enabled: true; consentVersion: number; precision: LocationPrecision } | { enabled: false },
  ): Promise<LocationState> {
    const now = this.now();
    if (wish.enabled) {
      if (wish.consentVersion !== LOCATION_CONSENT_VERSION) throw appError(409, "CONSENT_OUTDATED");
      const row = await this.prisma.locationShare.upsert({
        where: { userId },
        create: { userId, enabled: true, consentVersion: wish.consentVersion, consentedAt: now, precision: wish.precision },
        update: { enabled: true, consentVersion: wish.consentVersion, consentedAt: now, precision: wish.precision },
      });
      await audit(this.prisma, { actorType: "user", actorId: userId, action: "user.location_sharing_on", targetType: "user", targetId: userId });
      return toState(row);
    }
    const row = await this.prisma.locationShare.upsert({
      where: { userId },
      create: { userId, enabled: false },
      update: { enabled: false, latitude: null, longitude: null, accuracyM: null, capturedAt: null, receivedAt: null },
    });
    await audit(this.prisma, { actorType: "user", actorId: userId, action: "user.location_sharing_off", targetType: "user", targetId: userId });
    return toState(row);
  }

  /** Stores the latest point, replacing the previous one. */
  async report(userId: string, r: LocationReport): Promise<LocationState> {
    const now = this.now();
    const row = await this.prisma.locationShare.findUnique({ where: { userId } });
    if (!row?.enabled || row.consentVersion !== LOCATION_CONSENT_VERSION) throw appError(409, "SHARING_DISABLED");
    const captured = new Date(r.capturedAt);
    if (Number.isNaN(captured.getTime())) throw appError(400, "VALIDATION_FAILED");
    if (captured.getTime() > now.getTime() + MAX_FUTURE_MS || captured.getTime() < now.getTime() - MAX_FIX_AGE_MS) {
      throw appError(400, "VALIDATION_FAILED");
    }
    // ~1 m for precise, ~1 km for approximate (the user's choice in Android's permission dialog).
    const places = r.precision === "precise" ? 5 : 2;
    const updated = await this.prisma.locationShare.update({
      where: { userId },
      data: {
        latitude: round(r.lat, places),
        longitude: round(r.lng, places),
        accuracyM: r.accuracyM,
        precision: r.precision,
        capturedAt: captured,
        receivedAt: now,
      },
    });
    return toState(updated);
  }

  /** Every user's sharing state for the Admin Console list (no coordinates). */
  async overview(): Promise<Map<string, LocationShare>> {
    const rows = await this.prisma.locationShare.findMany();
    return new Map(rows.map((r) => [r.userId, r]));
  }

  /** The latest point for the administrator. Each view is audited and shown to the user. */
  async viewByAdmin(userId: string, adminId: string | null): Promise<LocationShare | null> {
    const row = await this.prisma.locationShare.findUnique({ where: { userId } });
    if (!row) return null;
    const updated = await this.prisma.locationShare.update({ where: { userId }, data: { lastViewedByAdminAt: this.now() } });
    await audit(this.prisma, { actorType: "admin", actorId: adminId, action: "admin.location_viewed", targetType: "user", targetId: userId });
    return updated;
  }
}

function round(value: number, places: number): number {
  const f = 10 ** places;
  return Math.round(value * f) / f;
}

function toState(row: LocationShare | null): LocationState {
  return {
    enabled: row?.enabled === true && row.consentVersion === LOCATION_CONSENT_VERSION,
    consentVersion: row?.consentVersion ?? null,
    currentConsentVersion: LOCATION_CONSENT_VERSION,
    precision: (row?.precision as LocationPrecision | null) ?? null,
    lastSentAt: row?.receivedAt?.toISOString() ?? null,
    lastViewedByAdminAt: row?.lastViewedByAdminAt?.toISOString() ?? null,
  };
}
