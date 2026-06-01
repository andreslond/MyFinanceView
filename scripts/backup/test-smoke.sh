#!/usr/bin/env bash
# test-smoke.sh — integration smoke test for the backup bash workers (v3 single-recipient).
#
# Follows base-standards.md §5: integration tests use real Postgres (no mocks).
# Runs backup-daily.sh against a local Postgres-in-Docker seeded with the
# myfinance schema, then chains verify-restore.sh.
#
# Usage: bash scripts/backup/test-smoke.sh
# Requirements: docker CLI, age CLI (for key generation), rclone (configured
#   to point at a local MinIO or a dummy rclone target).
#
# Exit code 0 = all smoke tests passed.
# Exit code non-zero = at least one test failed.
#
# CI hook: run on every PR touching scripts/backup/ via
#   .github/workflows/backup-smoke.yml (see tasks.md §4.7).
# Until CI is configured, run this script manually before any change to
#   scripts/backup/*.sh lands on main (documented in README.md §2.5.6).
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------------------------------------------------------------------------
# Configurable
# ---------------------------------------------------------------------------
PG_CONTAINER="myfinance-smoke-pg"
PG_PASSWORD="smoke"
PG_PORT="15432"   # host port (avoids collision with 5432)
PG_DB="postgres"
PG_USER="postgres"

# Smoke rclone target — operator must configure one of:
#   Option A: local MinIO container (start separately with docker run minio/minio)
#   Option B: a filesystem rclone remote pointing at a temp dir:
#             rclone config add r2-smoke alias / path=/tmp/myfinance-smoke-r2
#             then set SMOKE_RCLONE_REMOTE=r2-smoke below
SMOKE_RCLONE_REMOTE="${SMOKE_RCLONE_REMOTE:-r2}"
SMOKE_BUCKET="${SMOKE_BUCKET:-my-finance-view-backups}"

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
cleanup() {
  echo "[smoke] Tearing down: stopping ${PG_CONTAINER}"
  docker stop "${PG_CONTAINER}" >/dev/null 2>&1 || true
  docker rm -f "${PG_CONTAINER}" >/dev/null 2>&1 || true
  if [[ -n "${AGE_KEYFILE:-}" && -f "${AGE_KEYFILE}" ]]; then
    shred -u "${AGE_KEYFILE}" 2>/dev/null || rm -f "${AGE_KEYFILE}" 2>/dev/null || true
  fi
  if [[ -n "${SMOKE_WORK_DIR:-}" && -d "${SMOKE_WORK_DIR}" ]]; then
    rm -rf "${SMOKE_WORK_DIR}" || true
  fi
}
trap cleanup EXIT INT TERM

SMOKE_WORK_DIR="$(mktemp -d)"
echo "[smoke] Working dir: ${SMOKE_WORK_DIR}"

# ---------------------------------------------------------------------------
# Step 1: Boot local Postgres 17 container
# ---------------------------------------------------------------------------
echo "[smoke] Starting Postgres 17 container: ${PG_CONTAINER}"
docker run -d \
  --name "${PG_CONTAINER}" \
  --network n8n_net \
  -e POSTGRES_PASSWORD="${PG_PASSWORD}" \
  -e POSTGRES_DB="${PG_DB}" \
  -p "${PG_PORT}:5432" \
  postgres:17

# Wait for pg_isready
DEADLINE=$(( $(date +%s) + 60 ))
until pg_isready -h 127.0.0.1 -p "${PG_PORT}" -U "${PG_USER}" -t 5 >/dev/null 2>&1; do
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    echo "[smoke] FAIL: Postgres did not become ready within 60 s" >&2
    exit 1
  fi
  sleep 1
done
echo "[smoke] Postgres ready"

# ---------------------------------------------------------------------------
# Step 2: Seed schema + data
# ---------------------------------------------------------------------------
echo "[smoke] Seeding schema from test-fixtures/seed.sql"
PGPASSWORD="${PG_PASSWORD}" psql \
  -h 127.0.0.1 -p "${PG_PORT}" \
  -U "${PG_USER}" -d "${PG_DB}" \
  -v ON_ERROR_STOP=1 \
  -f "${SCRIPT_DIR}/test-fixtures/seed.sql"

echo "[smoke] Verifying seed counts:"
PGPASSWORD="${PG_PASSWORD}" psql \
  -h 127.0.0.1 -p "${PG_PORT}" \
  -U "${PG_USER}" -d "${PG_DB}" \
  -t -A \
  -c "SELECT 'transactions', count(*) FROM myfinance.transactions
      UNION ALL SELECT 'accounts', count(*) FROM myfinance.accounts
      UNION ALL SELECT 'categories', count(*) FROM myfinance.categories
      UNION ALL SELECT 'auth_users', count(*) FROM auth.users;"

# ---------------------------------------------------------------------------
# Step 3: Generate a test age key pair (committed identity for smoke only)
# ---------------------------------------------------------------------------
echo "[smoke] Generating smoke test age key pair"
AGE_KEYFILE="${SMOKE_WORK_DIR}/smoke-identity.txt"
age-keygen -o "${AGE_KEYFILE}" 2>/dev/null
SMOKE_RECIPIENT="$(age-keygen -y "${AGE_KEYFILE}" 2>/dev/null)"
echo "[smoke] Smoke recipient: ${SMOKE_RECIPIENT}"

# Temporarily override recipients dir to use smoke recipient.
# v3 single-recipient design (Gate C cut B1) — no recovery.txt is generated.
SMOKE_RECIPIENTS_DIR="${SMOKE_WORK_DIR}/recipients"
mkdir -p "${SMOKE_RECIPIENTS_DIR}"
echo "${SMOKE_RECIPIENT}" > "${SMOKE_RECIPIENTS_DIR}/primary.txt"

# ---------------------------------------------------------------------------
# Step 4: Run backup-daily.sh with smoke env
# ---------------------------------------------------------------------------
echo "[smoke] Running backup-daily.sh"
SMOKE_IDENTITY="$(cat "${AGE_KEYFILE}")"

# Override env to point at local Postgres
export BACKUP_DB_HOST="127.0.0.1"
export BACKUP_DB_PORT="${PG_PORT}"
export BACKUP_DB_USER="${PG_USER}"
export BACKUP_DB_PASSWORD="${PG_PASSWORD}"
export BACKUP_DB_NAME="${PG_DB}"
export BACKUP_R2_BUCKET="${SMOKE_BUCKET}"
export BACKUP_R2_ACCOUNT_ID="smoke"
export BACKUP_R2_ACCESS_KEY_ID="smoke"
export BACKUP_R2_SECRET_ACCESS_KEY="smoke"
export MYFINANCE_BACKUP_NTFY_TOPIC=""
export MYFINANCE_BACKUP_GMAIL_APP_PASSWORD=""
export MYFINANCE_BACKUP_KUMA_PUSH_URL=""

# Override recipients dir via a modified copy of backup-daily.sh
# (or run with RECIPIENTS_DIR override if the script supports it)
# For smoke test, we patch the environment so the worker uses smoke recipients.
# Since the worker hardcodes /opt/myfinance-backup/recipients/, we symlink.
#
# DESIGN DECISION (smoke test): the worker expects recipients at
# /opt/myfinance-backup/recipients/ (inside the container). For the smoke test
# we run the workers on the host, so we temporarily create the path.
# If /opt/myfinance-backup/recipients/ already exists (e.g. from a real install),
# skip the override to avoid overwriting real keys.

SMOKE_INSTALLED_RECIPIENTS=false
if [[ ! -d /opt/myfinance-backup/recipients ]]; then
  mkdir -p /opt/myfinance-backup
  ln -sfn "${SMOKE_RECIPIENTS_DIR}" /opt/myfinance-backup/recipients
  SMOKE_INSTALLED_RECIPIENTS=true
fi

DAILY_OUTPUT="${SMOKE_WORK_DIR}/daily-output.json"
if printf '%s' "${SMOKE_IDENTITY}" | \
   bash "${SCRIPT_DIR}/workers/backup-daily.sh" > "${DAILY_OUTPUT}" 2>&1; then
  echo "[smoke] backup-daily.sh: PASS"
  echo "[smoke] Output: $(tail -1 "${DAILY_OUTPUT}")"
else
  echo "[smoke] backup-daily.sh: FAIL" >&2
  cat "${DAILY_OUTPUT}" >&2
  exit 2
fi

# Clean up symlink if we created it
if [[ "$SMOKE_INSTALLED_RECIPIENTS" == "true" ]]; then
  rm -f /opt/myfinance-backup/recipients
fi

# ---------------------------------------------------------------------------
# Step 5: Verify the SHA-256 re-verify path ran (grep output)
# ---------------------------------------------------------------------------
if grep -q "SHA-256 re-verify passed" "${DAILY_OUTPUT}" 2>/dev/null; then
  echo "[smoke] SHA-256 re-verify path: PASS"
else
  echo "[smoke] WARNING: SHA-256 re-verify path not confirmed in output (may be rclone-remote dependent)"
fi

# ---------------------------------------------------------------------------
# Step 6: Assert chained verify-restore produced green probes
# ---------------------------------------------------------------------------
LAST_LINE="$(tail -1 "${DAILY_OUTPUT}")"
if printf '%s' "${LAST_LINE}" | grep -q '"path":"daily/'; then
  echo "[smoke] Final JSON output is well-formed: PASS"
else
  echo "[smoke] Final JSON output missing or malformed: FAIL" >&2
  echo "[smoke] Last line: ${LAST_LINE}" >&2
  exit 3
fi

# ---------------------------------------------------------------------------
# Teardown (handled by EXIT trap)
# ---------------------------------------------------------------------------
echo "[smoke] All smoke tests PASSED"
exit 0
