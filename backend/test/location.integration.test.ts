import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { PasswordHasher } from "../src/auth/passwords.js";
import { AuthService } from "../src/auth/service.js";
import { PrismaDatabase } from "../src/db/database.js";
import { LOCATION_CONSENT_VERSION, LocationService } from "../src/location/service.js";
import { resetDatabase, TEST_DB_URL, testConfig } from "./helpers.js";

describe.skipIf(!TEST_DB_URL)("location sharing (PostgreSQL)", () => {
  const config = testConfig({ DATABASE_URL: TEST_DB_URL ?? "postgresql://unused/x" });
  let database: PrismaDatabase;
  let auth: AuthService;
  let location: LocationService;
  let app: FastifyInstance;

  beforeAll(() => {
    database = new PrismaDatabase(config, "prisma/migrations");
    auth = new AuthService(database.prisma, new PasswordHasher({ memoryCost: 1024, timeCost: 1 }));
  });
  afterAll(async () => {
    await database.close();
  });
  beforeEach(async () => {
    await resetDatabase(database.prisma);
    location = new LocationService(database.prisma);
    app = buildApp({
      config,
      database: { checkReadiness: database.checkReadiness.bind(database), close: async () => {} },
      auth,
      location,
      logger: false,
    });
    await app.ready();
  });
  afterEach(async () => {
    await app.close();
  });

  async function signIn(name: string): Promise<{ id: string; token: string }> {
    const { user, temporaryPassword } = await auth.createUser(name, { type: "system", id: null });
    const first = (await app.inject({ method: "POST", url: "/api/v1/auth/login", payload: { username: name, password: temporaryPassword } })).json();
    const changed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/change-password",
      headers: { authorization: `Bearer ${first.accessToken}` },
      payload: { currentPassword: temporaryPassword, newPassword: `${name} brand new passphrase 77` },
    });
    return { id: user.id, token: changed.json().accessToken as string };
  }

  const call = (token: string, method: "GET" | "PUT", url: string, payload?: object) =>
    app.inject({ method, url: `/api/v1${url}`, headers: { authorization: `Bearer ${token}` }, ...(payload ? { payload } : {}) });

  const point = (over: Partial<Record<string, unknown>> = {}) => ({
    lat: 28.613939123,
    lng: 77.209023456,
    accuracyM: 12.5,
    capturedAt: new Date().toISOString(),
    precision: "precise",
    ...over,
  });

  it("is off until the user agrees; then keeps only the latest point, rounded", async () => {
    const alice = await signIn("alice");
    const before = (await call(alice.token, "GET", "/location-sharing")).json();
    expect(before).toMatchObject({ enabled: false, currentConsentVersion: LOCATION_CONSENT_VERSION, lastSentAt: null });

    // Sending a point while off is refused.
    const refused = await call(alice.token, "PUT", "/location-sharing/current", point());
    expect(refused.statusCode).toBe(409);
    expect(refused.json().error.code).toBe("SHARING_DISABLED");

    // An outdated consent text is refused.
    const old = await call(alice.token, "PUT", "/location-sharing", { enabled: true, consentVersion: LOCATION_CONSENT_VERSION + 1, precision: "precise" });
    expect(old.json().error.code).toBe("CONSENT_OUTDATED");

    const on = await call(alice.token, "PUT", "/location-sharing", { enabled: true, consentVersion: LOCATION_CONSENT_VERSION, precision: "precise" });
    expect(on.statusCode).toBe(200);
    expect(on.json().enabled).toBe(true);

    expect((await call(alice.token, "PUT", "/location-sharing/current", point())).statusCode).toBe(200);
    expect((await call(alice.token, "PUT", "/location-sharing/current", point({ lat: 19.07609, lng: 72.87766 }))).statusCode).toBe(200);
    const rows = await database.prisma.locationShare.findMany();
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ latitude: 19.07609, longitude: 72.87766 });

    // Approximate locations are stored at about 1 km.
    await call(alice.token, "PUT", "/location-sharing/current", point({ precision: "approximate" }));
    expect(await database.prisma.locationShare.findUnique({ where: { userId: alice.id } })).toMatchObject({ latitude: 28.61, longitude: 77.21 });
  });

  it("turning sharing off deletes the stored point", async () => {
    const alice = await signIn("alice");
    await call(alice.token, "PUT", "/location-sharing", { enabled: true, consentVersion: LOCATION_CONSENT_VERSION, precision: "precise" });
    await call(alice.token, "PUT", "/location-sharing/current", point());
    const off = await call(alice.token, "PUT", "/location-sharing", { enabled: false });
    expect(off.json()).toMatchObject({ enabled: false, lastSentAt: null });
    const row = await database.prisma.locationShare.findUnique({ where: { userId: alice.id } });
    expect(row).toMatchObject({ enabled: false, latitude: null, longitude: null, capturedAt: null });
  });

  it("rejects bad points: out of range, stale, or from the future", async () => {
    const alice = await signIn("alice");
    await call(alice.token, "PUT", "/location-sharing", { enabled: true, consentVersion: LOCATION_CONSENT_VERSION, precision: "precise" });
    expect((await call(alice.token, "PUT", "/location-sharing/current", point({ lat: 91 }))).statusCode).toBe(400);
    expect((await call(alice.token, "PUT", "/location-sharing/current", point({ accuracyM: 0 }))).statusCode).toBe(400);
    const stale = new Date(Date.now() - 2 * 60 * 60_000).toISOString();
    expect((await call(alice.token, "PUT", "/location-sharing/current", point({ capturedAt: stale }))).statusCode).toBe(400);
    const future = new Date(Date.now() + 60 * 60_000).toISOString();
    expect((await call(alice.token, "PUT", "/location-sharing/current", point({ capturedAt: future }))).statusCode).toBe(400);
    expect((await call(alice.token, "PUT", "/location-sharing/current", point({ extra: 1 }))).statusCode).toBe(400);
  });

  it("each user has their own row; an admin view is audited and shown to the user", async () => {
    const alice = await signIn("alice");
    const bob = await signIn("bob");
    await call(alice.token, "PUT", "/location-sharing", { enabled: true, consentVersion: LOCATION_CONSENT_VERSION, precision: "precise" });
    await call(alice.token, "PUT", "/location-sharing/current", point());
    expect((await call(bob.token, "GET", "/location-sharing")).json().enabled).toBe(false);

    const seen = await location.viewByAdmin(alice.id, null);
    expect(seen?.latitude).toBe(28.61394);
    const state = (await call(alice.token, "GET", "/location-sharing")).json();
    expect(state.lastViewedByAdminAt).not.toBeNull();
    const events = await database.prisma.auditEvent.findMany({ where: { action: "admin.location_viewed" } });
    expect(events).toHaveLength(1);
    // Coordinates never go into the audit log.
    const all = JSON.stringify((await database.prisma.auditEvent.findMany()).map((e) => [e.action, e.metadata]));
    expect(all).not.toContain("28.61");
    expect(all).not.toContain("77.20");
  });

  it("refuses anonymous requests", async () => {
    expect((await app.inject({ method: "GET", url: "/api/v1/location-sharing" })).statusCode).toBe(401);
    expect((await app.inject({ method: "PUT", url: "/api/v1/location-sharing/current", payload: point() })).statusCode).toBe(401);
  });
});
