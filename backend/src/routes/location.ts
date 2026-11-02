import type { FastifyInstance } from "fastify";
import { authOf, requireUser } from "../auth/plugin.js";
import type { AuthService } from "../auth/service.js";
import type { LocationPrecision, LocationService } from "../location/service.js";

const stateSchema = {
  type: "object",
  additionalProperties: false,
  required: ["enabled", "consentVersion", "currentConsentVersion", "precision", "lastSentAt", "lastViewedByAdminAt"],
  properties: {
    enabled: { type: "boolean" },
    consentVersion: { type: ["integer", "null"] },
    currentConsentVersion: { type: "integer" },
    precision: { type: ["string", "null"] },
    lastSentAt: { type: ["string", "null"] },
    lastViewedByAdminAt: { type: ["string", "null"] },
  },
} as const;

type SharingBody = { enabled: true; consentVersion: number; precision: LocationPrecision } | { enabled: false };

/** Location sharing with the administrator (docs/11, docs/14 §location; owner decision D-06). */
export async function locationRoutes(app: FastifyInstance, opts: { auth: AuthService; location: LocationService }) {
  const user = requireUser(opts.auth);

  app.get("/location-sharing", { onRequest: user, schema: { response: { 200: stateSchema } } }, async (request) =>
    opts.location.state(authOf(request).user.id),
  );

  app.put<{ Body: SharingBody }>(
    "/location-sharing",
    {
      onRequest: user,
      config: { rateLimit: { max: 20, timeWindow: 60_000 } },
      schema: {
        body: {
          oneOf: [
            {
              type: "object",
              additionalProperties: false,
              required: ["enabled", "consentVersion", "precision"],
              properties: {
                enabled: { const: true },
                consentVersion: { type: "integer", minimum: 1, maximum: 1000 },
                precision: { enum: ["approximate", "precise"] },
              },
            },
            { type: "object", additionalProperties: false, required: ["enabled"], properties: { enabled: { const: false } } },
          ],
        },
        response: { 200: stateSchema },
      },
    },
    async (request) => opts.location.setSharing(authOf(request).user.id, request.body),
  );

  app.put<{ Body: { lat: number; lng: number; accuracyM: number; capturedAt: string; precision: LocationPrecision } }>(
    "/location-sharing/current",
    {
      onRequest: user,
      // The app sends one point each time it is opened; this only stops a runaway loop.
      config: { rateLimit: { max: 6, timeWindow: 60_000 } },
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["lat", "lng", "accuracyM", "capturedAt", "precision"],
          properties: {
            lat: { type: "number", minimum: -90, maximum: 90 },
            lng: { type: "number", minimum: -180, maximum: 180 },
            accuracyM: { type: "number", exclusiveMinimum: 0, maximum: 100_000 },
            capturedAt: { type: "string", format: "date-time", maxLength: 40 },
            precision: { enum: ["approximate", "precise"] },
          },
        },
        response: { 200: stateSchema },
      },
    },
    async (request) => opts.location.report(authOf(request).user.id, request.body),
  );
}
