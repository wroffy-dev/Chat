#!/usr/bin/env bash
# Verifies grants.sql on a throwaway database: migrations as vc_migrate, then what vc_app and
# vc_maint may and may not do, then the backend's real flows as vc_app, then a backup and restore
# drill as vc_maint. For local machines and CI only — never point it at a real server.
#
# Needs: libpq variables for a SUPERUSER (PGHOST, PGPORT, PGUSER, PGPASSWORD), psql, node with
# backend/ built (npm ci && npm run build), and age for the backup step.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$PWD"

db="vc_roles_check_$$"
pw="roles-check-$RANDOM$RANDOM"      # throwaway password for throwaway roles on a test server
host="${PGHOST:-127.0.0.1}"; port="${PGPORT:-5432}"
url() { echo "postgresql://$1:$pw@$host:$port/$db"; }
asadmin() { psql --no-psqlrc --quiet -v ON_ERROR_STOP=1 "$@"; }
as() { local role=$1; shift; PGPASSWORD="$pw" psql --no-psqlrc --quiet --tuples-only -h "$host" -p "$port" -U "$role" -d "$db" -v ON_ERROR_STOP=1 "$@"; }
ok() { as "$1" -c "$2" >/dev/null && echo "  ok      $1: $2" || { echo "  FAILED  $1 should be allowed: $2"; exit 1; }; }
# Refused for lack of privilege specifically (not, say, by a trigger or a missing row).
no() {
  local err
  if err="$(as "$1" -c "$2" 2>&1 >/dev/null)"; then echo "  FAILED  $1 must be refused: $2"; exit 1; fi
  case "$err" in
    *"permission denied"* | *"must be owner"*) echo "  refused $1: $2" ;;
    *) echo "  FAILED  $1 refused for the wrong reason: $2 ($err)"; exit 1 ;;
  esac
}

cleanup() {
  [[ -n "${server_pid:-}" ]] && kill "$server_pid" 2>/dev/null || true
  asadmin -d postgres -c "DROP DATABASE IF EXISTS $db" || true
}
trap cleanup EXIT

for r in vc_migrate vc_app vc_maint; do
  asadmin -d postgres -c "DO \$\$BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$r') THEN CREATE ROLE $r LOGIN; END IF; END\$\$"
  asadmin -d postgres -c "ALTER ROLE $r LOGIN PASSWORD '$pw'"
done
asadmin -d postgres -c "CREATE DATABASE $db OWNER vc_migrate"

echo "migrations as vc_migrate"
(cd backend && MIGRATE_DATABASE_URL="$(url vc_migrate)" npx --no-install prisma migrate deploy >/dev/null)
asadmin -d "$db" -f deploy/postgres/grants.sql
asadmin -d "$db" -f deploy/postgres/grants.sql   # idempotent

echo "privileges"
ok vc_app   "SELECT count(*) FROM users"
ok vc_app   "SELECT count(*) FROM _prisma_migrations"
no vc_app   "DELETE FROM connections"
no vc_app   "UPDATE connections SET created_at = now()"
no vc_app   "DELETE FROM audit_events"
no vc_app   "UPDATE audit_events SET action = 'x'"
no vc_app   "DELETE FROM _prisma_migrations"
no vc_app   "CREATE TABLE t (x int)"
no vc_app   "DROP TABLE messages"
no vc_app   "TRUNCATE users"
ok vc_maint "SELECT count(*) FROM users"
ok vc_maint "DELETE FROM audit_events WHERE occurred_at < now() - interval '365 days'"
no vc_maint "DELETE FROM users"
no vc_maint "INSERT INTO audit_events (actor_type, action) VALUES ('system', 'x')"
no vc_maint "CREATE TABLE t (x int)"

echo "backend flows as vc_app"
export NODE_ENV=test LOG_LEVEL=warn ARGON2_MEMORY_KIB=1024
export ADMIN_TOTP_ENC_KEY="$(head -c 32 /dev/urandom | base64)"
DATABASE_URL="$(url vc_app)" PORT=3190 ADMIN_PORT=3191 node backend/dist/server.js &
server_pid=$!
for _ in $(seq 1 30); do curl -fs http://127.0.0.1:3190/readyz >/dev/null && break; sleep 0.5; done
curl -fs http://127.0.0.1:3190/readyz >/dev/null || { echo "  FAILED  /readyz as vc_app"; exit 1; }
echo "  ok      /readyz as vc_app"
(cd backend && DATABASE_URL="$(url vc_app)" API=http://127.0.0.1:3190 node "$root/scripts/smoke-flows.mjs")
DATABASE_URL="$(url vc_maint)" node backend/dist/cli.js maint:purge-audit

if command -v age >/dev/null; then
  echo "backup as vc_maint, restore drill"
  tmp="$(mktemp -d)"
  age-keygen -o "$tmp/key" 2>/dev/null
  PGUSER=vc_maint PGPASSWORD="$pw" PGDATABASE="$db" BACKUP_DIR="$tmp/out" \
    AGE_RECIPIENT="$(grep -o 'age1[0-9a-z]*' "$tmp/key")" deploy/backup/backup.sh
  PGDATABASE=postgres deploy/backup/restore-test.sh "$(ls "$tmp"/out/*.age)" "$tmp/key"
  rm -rf "$tmp"
fi
echo "roles verified"
