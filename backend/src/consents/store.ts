import { readFileSync } from "node:fs";

/**
 * Consent texts (docs/consents/README.md). Each lives in one file, backend/consents/<id>.json, the only
 * place its words and version are kept: the server reads it at start-up, serves it to the app
 * (GET /api/v1/consents/<id>) and accepts agreement only to its current version. Raising the version
 * makes every user read and agree again.
 */
export interface Consent {
  id: string;
  version: number;
  title: string;
  points: string[];
  agree: string;
}

export const CONSENT_IDS = ["location-sharing"] as const;
export type ConsentId = (typeof CONSENT_IDS)[number];

/** Reads and checks one consent file; a malformed file stops the server at start-up. */
export function loadConsent(id: ConsentId): Consent {
  // dist/consents/store.js and src/consents/store.ts both sit two levels below backend/.
  const raw = readFileSync(new URL(`../../consents/${id}.json`, import.meta.url), "utf8");
  const c = JSON.parse(raw) as Partial<Consent>;
  const ok =
    c.id === id &&
    Number.isInteger(c.version) && (c.version as number) >= 1 &&
    typeof c.title === "string" && c.title.length > 0 &&
    Array.isArray(c.points) && c.points.length > 0 && c.points.every((p) => typeof p === "string" && p.length > 0) &&
    typeof c.agree === "string" && c.agree.length > 0;
  if (!ok) throw new Error(`consents/${id}.json is malformed`);
  return c as Consent;
}

export const CONSENTS: Record<ConsentId, Consent> = { "location-sharing": loadConsent("location-sharing") };
