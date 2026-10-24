import { describe, expect, it } from "vitest";
import { ConfigError, loadConfig } from "../src/config/env.js";

const base = { DATABASE_URL: "postgresql://u:p@localhost:5432/db" };

describe("loadConfig", () => {
  it("applies defaults", () => {
    const config = loadConfig(base);
    expect(config.PORT).toBe(3000);
    expect(config.NODE_ENV).toBe("development");
    expect(config.TRUST_PROXY).toEqual([]);
    expect(config.BODY_LIMIT_BYTES).toBe(65_536);
  });

  it("parses numbers and the trusted proxy list", () => {
    const config = loadConfig({ ...base, PORT: "8080", TRUST_PROXY: "10.0.1.0/24, 127.0.0.1" });
    expect(config.PORT).toBe(8080);
    expect(config.TRUST_PROXY).toEqual(["10.0.1.0/24", "127.0.0.1"]);
  });

  it("reports every missing or invalid variable", () => {
    try {
      loadConfig({ PORT: "99999", NODE_ENV: "staging" });
      expect.unreachable();
    } catch (err) {
      expect(err).toBeInstanceOf(ConfigError);
      const problems = (err as ConfigError).problems.join("\n");
      expect(problems).toContain("DATABASE_URL");
      expect(problems).toContain("PORT");
      expect(problems).toContain("NODE_ENV");
    }
  });

  it("never echoes the value of an invalid variable", () => {
    const secretUrl = "mysql://admin:SuperSecret123@db.example.com/prod";
    try {
      loadConfig({ DATABASE_URL: secretUrl });
      expect.unreachable();
    } catch (err) {
      expect((err as Error).message).toContain("DATABASE_URL");
      expect((err as Error).message).not.toContain("SuperSecret123");
    }
  });
});
