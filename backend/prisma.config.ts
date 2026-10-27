import { defineConfig } from "prisma/config";

// Load backend/.env for local CLI use when present. Production supplies real environment variables.
try {
  process.loadEnvFile(".env");
} catch {
  // No .env file — rely on the process environment.
}

export default defineConfig({
  schema: "prisma/schema.prisma",
  migrations: {
    path: "prisma/migrations",
  },
  datasource: {
    // Migrations run with the schema-owning role (docs/13-database-design.md §1); the app role cannot run DDL.
    url: process.env["MIGRATE_DATABASE_URL"] ?? process.env["DATABASE_URL"] ?? "",
  },
});
