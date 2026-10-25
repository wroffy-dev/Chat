#!/usr/bin/env bash
# Encrypted PostgreSQL backup without message text (docs/16-deployment-and-operations.md §11, ADR-05).
#
# Connection: standard libpq variables (PGHOST, PGPORT, PGUSER, PGDATABASE, PGPASSWORD or PGPASSFILE),
# so no password appears on a command line.
# Required:  BACKUP_DIR      directory for the encrypted files
#            AGE_RECIPIENT   age public key ("age1..."); the matching private key is kept offline
# Optional:  BACKUP_STATUS_FILE  written with the UTC time of the last success (default $BACKUP_DIR/last-success)
#
# Message data is never written: the `messages` table keeps its structure but no rows. The dump is
# streamed straight into age, so no unencrypted copy touches the disk.
# Retention (decision D-12, 2026-10-25): only the last 24 hours of backups are kept. Older files in
# BACKUP_DIR are deleted after a successful backup, so the newest backup always survives.
set -euo pipefail
umask 077

: "${BACKUP_DIR:?BACKUP_DIR is required}"
: "${AGE_RECIPIENT:?AGE_RECIPIENT (age public key) is required}"
STATUS_FILE="${BACKUP_STATUS_FILE:-$BACKUP_DIR/last-success}"

mkdir -p "$BACKUP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/vaultchat-$stamp.dump.age"
partial="$out.partial"
trap 'rm -f "$partial"' EXIT

pg_dump --format=custom --no-owner --no-privileges \
  --exclude-table-data=messages \
  | age --encrypt --recipient "$AGE_RECIPIENT" > "$partial"

mv "$partial" "$out"
date -u +%Y-%m-%dT%H:%M:%SZ > "$STATUS_FILE"
echo "backup written: $out ($(wc -c < "$out") bytes)"

# D-12: keep 24 hours of backups. Runs only after the new file exists.
find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'vaultchat-*.dump.age' -o -name 'vaultchat-*.dump.age.partial' \) \
  -mmin +1440 ! -path "$out" -print -delete | sed 's/^/pruned: /'
