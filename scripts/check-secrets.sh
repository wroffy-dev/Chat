#!/usr/bin/env bash
# Fails if a tracked file looks like it holds a secret (CLAUDE.md: never commit secrets).
# A pattern check, not a guarantee: it catches the obvious mistakes (keys, credential files,
# connection strings with real-looking passwords) before they reach the repository.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
report() {
  echo "possible secret: $1"
  fail=1
}

# 1. Files that must never be tracked.
while IFS= read -r f; do
  case "$f" in
    *.example | *.example.*) ;;
    *.jks | *.keystore | *.p12 | *.pem | *.key | google-services.json | */google-services.json | *service-account*.json | .env | */.env | .env.* | */.env.* | */livekit.yaml | livekit.yaml)
      report "$f (file type must not be committed)" ;;
  esac
done < <(git ls-files)

# 2. Content patterns. Lockfiles and this script are skipped.
patterns=(
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  'AGE-SECRET-KEY-1[0-9A-Z]{20,}'
  'AKIA[0-9A-Z]{16}'
  'otpauth://totp/[^ ]*secret=[A-Z2-7]{16,}'
  '"private_key_id": *"[0-9a-f]{20,}"'
  '(vca|vcr|vcq)_[A-Za-z0-9_-]{43}'
)
for p in "${patterns[@]}"; do
  while IFS= read -r hit; do
    report "$hit"
  done < <(git ls-files -z | grep -zv -e 'package-lock.json$' -e '^scripts/check-secrets.sh$' | xargs -0 grep -nIE -e "$p" -- 2>/dev/null || true)
done

# 3. Connection strings with an inline password, unless it is an obvious placeholder or test value.
while IFS= read -r hit; do
  case "$hit" in
    # Placeholders, test values, and passwords taken from a shell variable ($pw, ${PW}).
    *'<'*'>'* | *ci-only* | *test-only* | *unused* | *placeholder* | *example* | *'${'* | *://*:\$*@*) ;;
    *) report "$hit" ;;
  esac
done < <(git ls-files -z | grep -zv -e 'package-lock.json$' -e '^scripts/check-secrets.sh$' | xargs -0 grep -nIoE -e 'postgres(ql)?://[^:/@ ]+:[^@ ]+@[^ "]+' -- 2>/dev/null || true)

if [[ $fail != 0 ]]; then
  echo "secret check failed: remove the value (use a placeholder like <secret>) or the file" >&2
  exit 1
fi
echo "secret check passed ($(git ls-files | wc -l) tracked files)"
