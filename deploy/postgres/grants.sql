-- Database role privileges (docs/13-database-design.md §1). Idempotent: run after EVERY migration,
-- as the database owner or a superuser:   psql -v ON_ERROR_STOP=1 -f deploy/postgres/grants.sql
--
-- The three login roles are created once by the operator, with passwords typed interactively
-- (\password), never stored in a file:
--   CREATE ROLE vc_migrate LOGIN;  -- owns the database and schema; used only by `prisma migrate deploy`
--   CREATE ROLE vc_app LOGIN;      -- the running backend
--   CREATE ROLE vc_maint LOGIN;    -- backups (read) and the audit-log retention job
--   CREATE DATABASE vaultchat OWNER vc_migrate;

-- Nobody but the owner creates objects; the app and maintenance roles only use them.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO vc_app, vc_maint;

-- Start from nothing on every run, then grant exactly what is needed.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM vc_app, vc_maint;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM vc_app, vc_maint;

-- App: read/write data, but connections and the audit log are append-only, and migration
-- bookkeeping is read-only (readiness checks it for pending migrations).
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO vc_app;
REVOKE UPDATE, DELETE ON connections, audit_events FROM vc_app;
REVOKE INSERT, UPDATE, DELETE ON _prisma_migrations FROM vc_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO vc_app;

-- Maintenance: read everything (pg_dump must lock every table, including `messages`, although it
-- does not dump its rows) and delete old audit events (D-12: 365 days).
GRANT SELECT ON ALL TABLES IN SCHEMA public TO vc_maint;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO vc_maint;
GRANT DELETE ON audit_events TO vc_maint;
