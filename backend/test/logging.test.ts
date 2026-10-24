import { describe, expect, it } from "vitest";
import { buildApp } from "../src/app.js";
import { captureLogs, FakeDatabase, testConfig } from "./helpers.js";

describe("logging", () => {
  it("redacts credentials and sensitive fields", async () => {
    const config = testConfig();
    const { lines, logger } = captureLogs(config);
    const app = buildApp({ config, database: new FakeDatabase(), logger });
    app.get("/test/log", async (req) => {
      req.log.info(
        { password: "pw-123", nested: { refreshToken: "rt-456", text: "private message", lat: 12.34 } },
        "sensitive",
      );
      return { ok: true };
    });
    await app.ready();

    await app.inject({
      method: "GET",
      url: "/test/log?token=query-secret",
      headers: { authorization: "Bearer at-789", cookie: "vc_admin=cookie-000" },
    });
    await app.close();

    const output = lines.join("");
    expect(output).toContain("sensitive");
    for (const secret of ["pw-123", "rt-456", "private message", "12.34", "at-789", "cookie-000", "query-secret"]) {
      expect(output).not.toContain(secret);
    }
  });

  it("logs request method and path but not client IPs", async () => {
    const config = testConfig();
    const { lines, logger } = captureLogs(config);
    const app = buildApp({ config, database: new FakeDatabase(), logger });
    await app.inject({ method: "GET", url: "/healthz", remoteAddress: "203.0.113.7" });
    await app.close();

    const output = lines.join("");
    expect(output).toContain('"path":"/healthz"');
    expect(output).not.toContain("203.0.113.7");
  });
});
