#!/usr/bin/env bash
# backup-preop.sh — on-demand pre-operation snapshot with chained verify.
# Called by workers.js (POST /run/preop). Identity arrives on stdin.
# Requires env var: REASON (validated against ^[A-Za-z0-9._+-]{3,60}$)
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/_common.sh"
source "${SCRIPT_DIR}/alert.sh"

# Slurp age identity from stdin before anything else consumes it (B7 fix)
IFS= read -rd '' IDENTITY_CONTENT < /dev/stdin || true
unset MYFINANCE_BACKUP_AGE_IDENTITY 2>/dev/null || true

BUCKET="${BACKUP_R2_BUCKET:-my-finance-view-backups}"
RECIPIENTS_DIR="/opt/myfinance-backup/recipients"
RUN_START=$(date +%s)

# ---------------------------------------------------------------------------
# 4.4.1 Validate REASON (B4 fix)
# ---------------------------------------------------------------------------
REASON="${REASON:-}"
REASON_REGEX='^[A-Za-z0-9._+-]{3,60}$'
if [[ -z "$REASON" ]] || ! printf '%s' "$REASON" | grep -qE "$REASON_REGEX"; then
  emit_json_result "{\"error\":\"invalid_reason\",\"regex\":\"${REASON_REGEX}\",\"got\":\"${REASON}\",\"example_accepted\":\"flyway-baseline\",\"example_accepted_2\":\"v4.1-migration\"}"
  exit 2
fi

log_info "=== backup-preop.sh START reason=${REASON} ==="

# ---------------------------------------------------------------------------
# Working directory
# ---------------------------------------------------------------------------
WORK_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORK_DIR" || true
}
trap cleanup EXIT

LOG_FILE="${WORK_DIR}/run.log"
exec 2> >(tee -a "$LOG_FILE" >&2)

# ---------------------------------------------------------------------------
# pg_isready precheck (same as backup-daily.sh 4.3.0)
# ---------------------------------------------------------------------------
pg_isready -h "${BACKUP_DB_HOST}" -p "${BACKUP_DB_PORT:-5432}" -t 5 || {
  log_error "Supabase pooler ${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432} unreachable."
  exit 3
}

# ---------------------------------------------------------------------------
# Same dump pipeline as backup-daily.sh 4.3.1–4.3.3
# ---------------------------------------------------------------------------
log_info "Dumping auth.users"
PGPASSWORD="${BACKUP_DB_PASSWORD}" pg_dump \
  -Fc -Z 9 \
  -t auth.users \
  -f "${WORK_DIR}/auth-users.dump" \
  "postgresql://${BACKUP_DB_USER}@${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432}/${BACKUP_DB_NAME:-postgres}?sslmode=require"

log_info "Dumping myfinance schema"
PGPASSWORD="${BACKUP_DB_PASSWORD}" pg_dump \
  -Fc -Z 9 \
  -n myfinance \
  -f "${WORK_DIR}/myfinance.dump" \
  "postgresql://${BACKUP_DB_USER}@${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432}/${BACKUP_DB_NAME:-postgres}?sslmode=require"

TIMESTAMP_UTC="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
TIMESTAMP_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
ARTEFACT_NAME="${TIMESTAMP_UTC}-${REASON}.tar.age"

cat > "${WORK_DIR}/README.txt" <<EOF
MyFinanceView pre-operation snapshot
Generated: ${TIMESTAMP_ISO}
Reason: ${REASON}
Contents: auth-users.dump, myfinance.dump
See scripts/backup/README.md §2.5.4 for restore instructions.
EOF

tar -cf "${WORK_DIR}/snapshot.tar" -C "${WORK_DIR}" \
  auth-users.dump myfinance.dump README.txt

# ---------------------------------------------------------------------------
# Single-recipient age encryption (v3 — Gate C cut B1, same as daily)
# ---------------------------------------------------------------------------
log_info "Encrypting with primary recipient"
age \
  -r "$(cat "${RECIPIENTS_DIR}/primary.txt")" \
  -o "${WORK_DIR}/${ARTEFACT_NAME}" \
  "${WORK_DIR}/snapshot.tar"

LOCAL_SHA256="$(sha256sum "${WORK_DIR}/${ARTEFACT_NAME}" | awk '{print $1}')"
log_info "Local SHA-256: ${LOCAL_SHA256}"

# ---------------------------------------------------------------------------
# 4.4.2 Upload to R2 pre-op/
# ---------------------------------------------------------------------------
log_info "Uploading to r2:${BUCKET}/pre-op/${ARTEFACT_NAME}"
rclone copy "${WORK_DIR}/${ARTEFACT_NAME}" "r2:${BUCKET}/pre-op/"
rclone check "${WORK_DIR}/${ARTEFACT_NAME}" "r2:${BUCKET}/pre-op/" --one-way

# ---------------------------------------------------------------------------
# 4.4.2b Post-upload SHA-256 re-verify (M12 fix)
# ---------------------------------------------------------------------------
log_info "Re-downloading pre-op artefact for SHA-256 check"
REDOWNLOAD_DIR="${WORK_DIR}/r2-redownload"
mkdir -p "${REDOWNLOAD_DIR}"
rclone copy "r2:${BUCKET}/pre-op/${ARTEFACT_NAME}" "${REDOWNLOAD_DIR}/"
R2_SHA256="$(sha256sum "${REDOWNLOAD_DIR}/${ARTEFACT_NAME}" | awk '{print $1}')"

if [[ "$LOCAL_SHA256" != "$R2_SHA256" ]]; then
  log_error "SHA-256 mismatch: local=${LOCAL_SHA256} r2=${R2_SHA256}"
  QUARANTINE_KEY="quarantine/$(date -u +%Y-%m-%dT%H-%M-%SZ)-pre-op-${ARTEFACT_NAME}"
  rclone moveto "r2:${BUCKET}/pre-op/${ARTEFACT_NAME}" "r2:${BUCKET}/${QUARANTINE_KEY}"
  dispatch_alert "MyFinance pre-op backup UPLOAD CORRUPTED" \
    "SHA-256 mismatch. Quarantined to: ${QUARANTINE_KEY}" || true
  emit_json_result "{\"error\":\"upload_corrupted\",\"local_sha256\":\"${LOCAL_SHA256}\",\"r2_sha256\":\"${R2_SHA256}\",\"quarantined_to\":\"${QUARANTINE_KEY}\"}"
  exit 7
fi
log_info "SHA-256 re-verify passed"

# ---------------------------------------------------------------------------
# 4.4.3 Chained verify-restore (NOT via HTTP — same-container subprocess)
# ---------------------------------------------------------------------------
log_info "Chaining verify-restore for pre-op/${ARTEFACT_NAME}"
VERIFY_SCRIPT="${SCRIPT_DIR}/verify-restore.sh"

VERIFY_RESULT_FILE="${WORK_DIR}/verify-result.json"
VERIFY_FAILED=false

if printf '%s' "${IDENTITY_CONTENT}" | \
   bash "${VERIFY_SCRIPT}" --target "pre-op/${ARTEFACT_NAME}" > "${VERIFY_RESULT_FILE}"; then
  VERIFY_JSON="$(cat "${VERIFY_RESULT_FILE}")"
else
  VERIFY_FAILED=true
fi

# ---------------------------------------------------------------------------
# 4.4.4 Verify failure path
# ---------------------------------------------------------------------------
if [[ "$VERIFY_FAILED" == "true" ]]; then
  log_error "Verify FAILED for pre-op/${ARTEFACT_NAME} — quarantining"
  QUARANTINE_KEY="quarantine/$(date -u +%Y-%m-%dT%H-%M-%SZ)-pre-op-${ARTEFACT_NAME}"
  rclone moveto "r2:${BUCKET}/pre-op/${ARTEFACT_NAME}" "r2:${BUCKET}/${QUARANTINE_KEY}" || true
  dispatch_alert "MyFinance pre-op backup VERIFY FAILED" \
    "Reason: ${REASON} | Quarantined: ${QUARANTINE_KEY}" || true
  # last-preop.json is NOT updated on failure
  emit_json_result "{\"error\":\"verify_failed\",\"quarantined_to\":\"${QUARANTINE_KEY}\"}"
  exit 8
fi

# ---------------------------------------------------------------------------
# 4.4.5 Verify success — upsert status/last-preop.json
# ---------------------------------------------------------------------------
VERIFY_PROBES="$(printf '%s' "${VERIFY_JSON}" | grep -o '"probes":\[.*\]' | head -1 || echo '"probes":[]')"
PREOP_STATUS_JSON="{\"artefact\":\"pre-op/${ARTEFACT_NAME}\",\"sha256\":\"${LOCAL_SHA256}\",\"timestamp\":\"${TIMESTAMP_ISO}\",${VERIFY_PROBES}}"

printf '%s' "${PREOP_STATUS_JSON}" | rclone rcat "r2:${BUCKET}/status/last-preop.json"

log_info "=== backup-preop.sh SUCCESS reason=${REASON} ==="
emit_json_result "{\"artefact\":\"pre-op/${ARTEFACT_NAME}\",\"sha256\":\"${LOCAL_SHA256}\",\"verifyResult\":${VERIFY_JSON}}"
