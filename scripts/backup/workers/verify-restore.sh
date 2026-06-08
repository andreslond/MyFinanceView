#!/usr/bin/env bash
# verify-restore.sh — decrypt a snapshot from R2 and restore it into an ephemeral
# Postgres:17 container, then run verify-queries.sql probes.
#
# Usage (called by workers.js via stdin identity):
#   verify-restore.sh [--target <r2-relative-path>]
#
# Identity is read from stdin (Node pipes it in; see §3.6 workers.js).
# If no --target is given, defaults to the newest object under daily/.
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/_common.sh"
source "${SCRIPT_DIR}/alert.sh"

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
TARGET=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --target)
      TARGET="$2"
      shift 2
      ;;
    *)
      log_error "Unknown argument: $1"
      exit 1
      ;;
  esac
done

BUCKET="${BACKUP_R2_BUCKET:-my-finance-view-backups}"
# VERIFY_DIR must live under the host bind-mount (/var/lib/myfinance-backup)
# because the ephemeral postgres:17 container we spawn via docker-from-docker
# mounts paths from the HOST namespace, not the runner container's namespace.
# The previous tmpfs path /var/lib/myfinance-verify was visible inside the
# runner but not to other containers — the docker run -v failed with
# "No such file or directory" when pg_restore tried /backup/auth-users.dump.
# Trade-off accepted: plaintext dumps live briefly on disk during verify
# (vs never with the original tmpfs design) and are shredded by the EXIT
# trap below. The encrypted long-term storage on R2 is unaffected.

# ---------------------------------------------------------------------------
# Determine target
# ---------------------------------------------------------------------------
if [[ -z "$TARGET" ]]; then
  log_info "No --target specified; finding newest object under daily/"
  TARGET="daily/$(rclone lsf --files-only "r2:${BUCKET}/daily/" | sort -r | head -n 1)"
  if [[ "$TARGET" == "daily/" ]]; then
    log_error "No objects found under r2:${BUCKET}/daily/"
    exit 2
  fi
fi
log_info "Verify target: ${TARGET}"

# ---------------------------------------------------------------------------
# UUID-derived container name (M16 fix) — no collisions across parallel runs
# ---------------------------------------------------------------------------
VERIFY_CONTAINER="myfinance-verify-$(uuidgen | tr -d '-' | head -c 16)"
VERIFY_DIR="/var/lib/myfinance-backup/verify-work/${VERIFY_CONTAINER}"
mkdir -p "${VERIFY_DIR}"
# Dir must be traversable by the postgres container (uid 999 inside
# postgres:17). The .identity file written below has an explicit umask 0177
# so it stays 0600 root-only regardless of the parent dir mode. The dump
# files written by tar extract use the runner's default umask (0022 = 0644)
# which is the level postgres uid needs to pg_restore from /backup.
chmod 0755 "${VERIFY_DIR}"

# ---------------------------------------------------------------------------
# EXIT trap — stop ephemeral postgres + shred identity + remove work dir
# ---------------------------------------------------------------------------
cleanup() {
  docker stop "$VERIFY_CONTAINER" >/dev/null 2>&1 || true
  shred -u "${VERIFY_DIR}/.identity" 2>/dev/null || rm -f "${VERIFY_DIR}/.identity" 2>/dev/null || true
  # Shred the decrypted dump files so plaintext does not linger on disk.
  # find avoids a glob expansion error when the dir is already empty.
  find "${VERIFY_DIR}" -type f -exec shred -u {} \; 2>/dev/null || true
  rm -rf "${VERIFY_DIR}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# 4.5.3 Read age identity from stdin → write to verify dir (B7 fix)
# ---------------------------------------------------------------------------
cd "${VERIFY_DIR}"

# Read from stdin; Node closes stdin after writing so read -rd '' gets EOF
IFS= read -rd '' AGE_IDENTITY || true
AGE_IDENTITY="${AGE_IDENTITY:-}"

# Defensive scrub in case the env var was ever set
unset MYFINANCE_BACKUP_AGE_IDENTITY 2>/dev/null || true

if [[ -z "$AGE_IDENTITY" ]]; then
  log_error "No age identity received on stdin — cannot decrypt snapshot"
  exit 3
fi

# Write identity to tmpfs (mode 0600 via umask)
( umask 0177 && printf '%s\n' "$AGE_IDENTITY" > "${VERIFY_DIR}/.identity" )

# Scrub the bash variable
AGE_IDENTITY=""

# ---------------------------------------------------------------------------
# 4.5.2 Download snapshot from R2
# ---------------------------------------------------------------------------
SNAPSHOT_FILE="$(basename "${TARGET}")"
log_info "Downloading snapshot from r2:${BUCKET}/${TARGET}"
rclone copy "r2:${BUCKET}/${TARGET}" "${VERIFY_DIR}/"

# ---------------------------------------------------------------------------
# Decrypt
# ---------------------------------------------------------------------------
log_info "Decrypting ${SNAPSHOT_FILE}"
age -d -i "${VERIFY_DIR}/.identity" "${VERIFY_DIR}/${SNAPSHOT_FILE}" > "${VERIFY_DIR}/snapshot.tar"

# ---------------------------------------------------------------------------
# 4.5.4 Extract tar
# ---------------------------------------------------------------------------
log_info "Extracting snapshot.tar"
tar -xf "${VERIFY_DIR}/snapshot.tar" -C "${VERIFY_DIR}/"

# ---------------------------------------------------------------------------
# 4.5.6 Spawn ephemeral Postgres with DNS wait loop (M17 fix)
# ---------------------------------------------------------------------------
log_info "Starting ephemeral container: ${VERIFY_CONTAINER}"
docker run -d --rm \
  --name "${VERIFY_CONTAINER}" \
  --network "${BACKUP_VERIFY_NETWORK:-n8n_n8n_net}" \
  -v "${VERIFY_DIR}:/backup:ro" \
  -e POSTGRES_PASSWORD=verify \
  -e POSTGRES_DB=postgres \
  postgres:17

# DNS wait loop (M17 fix)
for i in $(seq 1 10); do
  if getent hosts "${VERIFY_CONTAINER}" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
getent hosts "${VERIFY_CONTAINER}" >/dev/null 2>&1 || {
  log_error "Docker DNS never registered ${VERIFY_CONTAINER} after 10 s"
  exit 4
}

# Wait for Postgres to be ready (max 60 s)
DEADLINE=$(( $(date +%s) + 60 ))
until pg_isready -h "${VERIFY_CONTAINER}" -p 5432 -U postgres -t 5 >/dev/null 2>&1; do
  if [[ $(date +%s) -ge $DEADLINE ]]; then
    log_error "Postgres in ${VERIFY_CONTAINER} did not become ready within 60 s"
    exit 5
  fi
  sleep 1
done
log_info "Postgres ready in ${VERIFY_CONTAINER}"

# ---------------------------------------------------------------------------
# 4.5.7 Pre-create auth schema stub (B2 fix); restore dumps in order
# ---------------------------------------------------------------------------
log_info "Creating auth schema stub"
PGPASSWORD=verify psql -h "${VERIFY_CONTAINER}" -U postgres -d postgres \
  -v ON_ERROR_STOP=1 \
  -c "CREATE SCHEMA IF NOT EXISTS auth; CREATE TABLE IF NOT EXISTS auth.users (id uuid PRIMARY KEY);"

log_info "Restoring auth-users.dump (data only)"
PGPASSWORD=verify pg_restore \
  -h "${VERIFY_CONTAINER}" -p 5432 -U postgres -d postgres \
  --data-only --table=users -Fc \
  "/backup/auth-users.dump"

log_info "Restoring myfinance.dump"
PGPASSWORD=verify pg_restore \
  -h "${VERIFY_CONTAINER}" -p 5432 -U postgres -d postgres \
  -Fc "/backup/myfinance.dump"

# ---------------------------------------------------------------------------
# 4.5.8 Run verify probes (NO latest_transaction_age_days — B3 fix)
# ---------------------------------------------------------------------------
QUERIES_FILE="${SCRIPT_DIR}/../verify-queries.sql"

run_probe() {
  local key="$1"
  local query="$2"
  local threshold="$3"
  local op="$4"  # "ge" (>=) or "le" (<=)

  local result
  result=$(PGPASSWORD=verify psql -h "${VERIFY_CONTAINER}" -U postgres -d postgres \
    -t -A -c "${query}" 2>/dev/null || echo "-1")
  result="${result//[[:space:]]/}"

  local passed=false
  if [[ "$op" == "ge" && "$result" -ge "$threshold" ]] 2>/dev/null; then
    passed=true
  elif [[ "$op" == "le" && "$result" -le "$threshold" ]] 2>/dev/null; then
    passed=true
  fi

  printf '{"probe":"%s","result":%s,"threshold":%s,"op":"%s","passed":%s}' \
    "$key" "$result" "$threshold" "$op" "$passed"
}

log_info "Running verify probes"
PROBE_TXNS=$(run_probe "transactions_count" "SELECT count(*) FROM myfinance.transactions" 300 "ge")
PROBE_ACCTS=$(run_probe "accounts_count"    "SELECT count(*) FROM myfinance.accounts"     1   "ge")
PROBE_CATS=$(run_probe  "categories_count"  "SELECT count(*) FROM myfinance.categories"   19  "ge")
PROBE_AUTH=$(run_probe  "auth_users_count"  "SELECT count(*) FROM auth.users"             1   "ge")

# Check if any probe failed
ALL_PASSED=true
for probe_json in "$PROBE_TXNS" "$PROBE_ACCTS" "$PROBE_CATS" "$PROBE_AUTH"; do
  if printf '%s' "$probe_json" | grep -q '"passed":false'; then
    ALL_PASSED=false
    break
  fi
done

PROBES_JSON="[${PROBE_TXNS},${PROBE_ACCTS},${PROBE_CATS},${PROBE_AUTH}]"
TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ---------------------------------------------------------------------------
# 4.5.10 Upsert status/last-verify.json to R2
# ---------------------------------------------------------------------------
VERIFY_STATUS_FILE="$(mktemp)"
trap 'rm -f "$VERIFY_STATUS_FILE"; cleanup' EXIT INT TERM

cat > "${VERIFY_STATUS_FILE}" <<EOF
{
  "target": "${TARGET}",
  "timestamp": "${TIMESTAMP}",
  "probes": ${PROBES_JSON},
  "allPassed": ${ALL_PASSED}
}
EOF

rclone rcat "r2:${BUCKET}/status/last-verify.json" < "${VERIFY_STATUS_FILE}"
rm -f "${VERIFY_STATUS_FILE}"

# ---------------------------------------------------------------------------
# 4.5.11 Exit with appropriate code
# ---------------------------------------------------------------------------
if [[ "$ALL_PASSED" != "true" ]]; then
  log_error "Verify probes FAILED: ${PROBES_JSON}"
  dispatch_alert "MyFinance verify-restore FAILED" "Target: ${TARGET} | Probes: ${PROBES_JSON}" || true
  exit 6
fi

log_info "All verify probes passed"

# Emit final JSON result (consumed by workers.js)
emit_json_result "{\"target\":\"${TARGET}\",\"timestamp\":\"${TIMESTAMP}\",\"probes\":${PROBES_JSON},\"allPassed\":true}"
