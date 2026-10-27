/**
 * Applies the database role privileges in prisma/grants.sql (docs/13-database-design.md §1) as the
 * schema-owning role. Runs after `prisma migrate deploy` in the migrate image, so new tables get the
 * right privileges on every deployment. Idempotent. Never prints the connection string.
 *
 *   MIGRATE_DATABASE_URL=postgresql://vc_migrate:…@host/vaultchat node dist/db/apply-grants.js
 */
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import pg from "pg";

const url = process.env.MIGRATE_DATABASE_URL;
if (!url) {
  console.error("MIGRATE_DATABASE_URL is not set");
  process.exit(1);
}

const sql = await readFile(fileURLToPath(new URL("../../prisma/grants.sql", import.meta.url)), "utf8");
const client = new pg.Client({ connectionString: url });
try {
  await client.connect();
  // One transaction: either every grant applies or none does.
  await client.query("BEGIN");
  await client.query(sql);
  await client.query("COMMIT");
  process.stdout.write("grants applied\n");
} catch (err) {
  await client.query("ROLLBACK").catch(() => undefined);
  console.error("applying grants failed:", (err as Error).message);
  process.exitCode = 1;
} finally {
  await client.end().catch(() => undefined);
}
