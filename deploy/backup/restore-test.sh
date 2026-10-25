#!/usr/bin/env bash
# Restore drill (docs/16 §11): decrypts a backup into a NEW scratch database, checks it, drops it.
# Never touches any other database.
#
# Connection: libpq variables for a role that may create databases (PGHOST, PGPORT, PGUSER,
# PGPASSWORD or PGPASSFILE). PGDATABASE is only used to connect for CREATE/DROP DATABASE.
# Usage:     restore-test.sh <backup.dump.age> <age-identity-file>
# Optional:  KEEP_RESTORE=1 keeps the scratch database for inspection.
set -euo pipefail
umask 077

backup="${1:?usage: restore-test.sh <backup.dump.age> <age-identity-file>}"
identity="${2:?usage: restore-test.sh <backup.dump.age> <age-identity-file>}"
scratch="restore_test_$(date -u +%Y%m%d%H%M%S)_$$"

created=0
cleanup() {
  if [[ $created == 1 && "${KEEP_RESTORE:-0}" != 1 ]]; then
    psql --quiet --no-psqlrc -v ON_ERROR_STOP=1 -c "DROP DATABASE \"$scratch\"" >/dev/null
  fi
}
trap cleanup EXIT

psql --quiet --no-psqlrc -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$scratch\"" >/dev/null
created=1

age --decrypt --identity "$identity" "$backup" | pg_restore --no-owner --no-privileges --exit-on-error --dbname "$scratch"

q() { psql --no-psqlrc --tuples-only --no-align -v ON_ERROR_STOP=1 --dbname "$scratch" -c "$1"; }
migrations="$(q 'SELECT count(*) FROM _prisma_migrations WHERE finished_at IS NOT NULL')"
users="$(q 'SELECT count(*) FROM users')"
connections="$(q 'SELECT count(*) FROM connections')"
conversations="$(q 'SELECT count(*) FROM conversations')"
messages="$(q 'SELECT count(*) FROM messages')"

echo "restored into $scratch: migrations=$migrations users=$users connections=$connections conversations=$conversations messages=$messages"
if [[ "$messages" != 0 ]]; then
  echo "FAIL: the backup contains message rows" >&2
  exit 1
fi
if [[ "$migrations" == 0 ]]; then
  echo "FAIL: no applied migrations in the backup" >&2
  exit 1
fi
echo "restore test passed"
