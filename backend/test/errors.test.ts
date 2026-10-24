import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "../src/app.js";
import { AppError } from "../src/errors.js";
import { FakeDatabase, testConfig } from "./helpers.js";

let app: FastifyInstance;

beforeEach(async () => {
  app = buildApp({ config: testConfig({ BODY_LIMIT_BYTES: "1024" }), database: new FakeDatabase(), logger: false });
  // Test-only routes exercising the shared error handling.
  app.post(
    "/test/echo",
    {
      schema: {
        body: {
          type: "object",
          required: ["name"],
          additionalProperties: false,
          properties: { name: { type: "string", minLength: 1, maxLength: 10 } },
        },
      },
    },
    async (req) => req.body,
  );
  app.get("/test/boom", async () => {
    throw new Error("database password is hunter2 at /srv/app/secret.ts:42");
  });
  app.get("/test/conflict", async () => {
    throw new AppError(409, "CONFLICT", "Already done.");
  });
  await app.ready();
});

afterEach(async () => {
  await app.close();
});

describe("error responses", () => {
  it("returns the standard 404 body for unknown routes", async () => {
    const res = await app.inject({ method: "GET", url: "/nope" });
    expect(res.statusCode).toBe(404);
    const body = res.json();
    expect(body.error.code).toBe("NOT_FOUND");
    expect(body.error.requestId).toBe(res.headers["x-request-id"]);
  });

  it("returns VALIDATION_FAILED with details for a missing field", async () => {
    const res = await app.inject({ method: "POST", url: "/test/echo", payload: {} });
    expect(res.statusCode).toBe(400);
    const body = res.json();
    expect(body.error.code).toBe("VALIDATION_FAILED");
    expect(body.error.details).toEqual([{ path: "body/name", rule: "required" }]);
  });

  it("rejects unknown fields instead of stripping them", async () => {
    const res = await app.inject({ method: "POST", url: "/test/echo", payload: { name: "a", isAdmin: true } });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.details).toEqual([{ path: "body/isAdmin", rule: "additionalProperties" }]);
  });

  it("accepts a valid body", async () => {
    const res = await app.inject({ method: "POST", url: "/test/echo", payload: { name: "ok" } });
    expect(res.statusCode).toBe(200);
    expect(res.json()).toEqual({ name: "ok" });
  });

  it("returns BAD_REQUEST for malformed JSON", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/test/echo",
      headers: { "content-type": "application/json" },
      payload: "{not json",
    });
    expect(res.statusCode).toBe(400);
    expect(res.json().error.code).toBe("BAD_REQUEST");
  });

  it("returns PAYLOAD_TOO_LARGE above the body limit", async () => {
    const res = await app.inject({ method: "POST", url: "/test/echo", payload: { name: "x".repeat(5000) } });
    expect(res.statusCode).toBe(413);
    expect(res.json().error.code).toBe("PAYLOAD_TOO_LARGE");
  });

  it("returns UNSUPPORTED_MEDIA_TYPE for non-JSON bodies", async () => {
    const res = await app.inject({
      method: "POST",
      url: "/test/echo",
      headers: { "content-type": "application/xml" },
      payload: "<a/>",
    });
    expect(res.statusCode).toBe(415);
    expect(res.json().error.code).toBe("UNSUPPORTED_MEDIA_TYPE");
  });

  it("hides internal error details", async () => {
    const res = await app.inject({ method: "GET", url: "/test/boom" });
    expect(res.statusCode).toBe(500);
    expect(res.json().error.code).toBe("INTERNAL_ERROR");
    expect(res.body).not.toContain("hunter2");
    expect(res.body).not.toContain("secret.ts");
  });

  it("passes through AppError code and message", async () => {
    const res = await app.inject({ method: "GET", url: "/test/conflict" });
    expect(res.statusCode).toBe(409);
    expect(res.json().error).toMatchObject({ code: "CONFLICT", message: "Already done." });
  });
});
