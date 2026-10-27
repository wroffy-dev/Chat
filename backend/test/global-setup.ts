import { execFileSync } from "node:child_process";

/** Applies migrations to the disposable test database before integration tests run. */
export default function setup() {
  const url = process.env["TEST_DATABASE_URL"];
  if (!url) return;
  execFileSync("npx", ["prisma", "migrate", "deploy"], {
    env: { ...process.env, DATABASE_URL: url, MIGRATE_DATABASE_URL: url },
    stdio: "pipe",
  });
}
