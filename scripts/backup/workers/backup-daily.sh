#!/usr/bin/env bash
# backup-daily.sh — daily pg_dump → age encrypt → R2 upload → chained verify.
# Called by workers.js (POST /run/daily). Identity arrives on stdin.
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/_common.sh"
source "${SCRIPT_DIR}/alert.sh"

BUCKET="${BACKUP_R2_BUCKET:-my-finance-view-backups}"
RECIPIENTS_DIR="/opt/myfinance-backup/recipients"
RUN_START=$(date +%s)

# ---------------------------------------------------------------------------
# Slurp age identity from stdin (piped by workers.js) — must be first read
# so we capture it before any subprocess consumes stdin (B7 fix).
# ---------------------------------------------------------------------------
IFS= read -rd '' IDENTITY_CONTENT < /dev/stdin || true
unset MYFINANCE_BACKUP_AGE_IDENTITY 2>/dev/null || true

# ---------------------------------------------------------------------------
# Working directory
# ---------------------------------------------------------------------------
WORK_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$WORK_DIR" || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Capture log tail for failure alert
# ---------------------------------------------------------------------------
LOG_FILE="${WORK_DIR}/run.log"
exec 2> >(tee -a "$LOG_FILE" >&2)

log_info "=== backup-daily.sh START ==="

# ---------------------------------------------------------------------------
# 4.3.0 pg_isready precheck
# ---------------------------------------------------------------------------
pg_isready -h "${BACKUP_DB_HOST}" -p "${BACKUP_DB_PORT:-5432}" -t 5 || {
  log_error "Supabase pooler ${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432} unreachable. If pg_dump previously worked, the pooler hostname may have moved — check Supabase Dashboard → Connect → Session pooler and update .env.local."
  exit 3
}

# ---------------------------------------------------------------------------
# 4.3.1 pg_dump auth.users (DDL + rows, full table scope)
# ---------------------------------------------------------------------------
log_info "Dumping auth.users"
PGPASSWORD="${BACKUP_DB_PASSWORD}" pg_dump \
  -Fc -Z 9 \
  -t auth.users \
  -f "${WORK_DIR}/auth-users.dump" \
  "postgresql://${BACKUP_DB_USER}@${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432}/${BACKUP_DB_NAME:-postgres}?sslmode=require"

# ---------------------------------------------------------------------------
# 4.3.2 pg_dump myfinance schema
# ---------------------------------------------------------------------------
log_info "Dumping myfinance schema"
PGPASSWORD="${BACKUP_DB_PASSWORD}" pg_dump \
  -Fc -Z 9 \
  -n myfinance \
  -f "${WORK_DIR}/myfinance.dump" \
  "postgresql://${BACKUP_DB_USER}@${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432}/${BACKUP_DB_NAME:-postgres}?sslmode=require"

# ---------------------------------------------------------------------------
# 4.3.3 README.txt + tar bundle
# ---------------------------------------------------------------------------
TODAY="$(date -u +%Y-%m-%d)"
TIMESTAMP_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

cat > "${WORK_DIR}/README.txt" <<EOF
MyFinanceView database snapshot
Generated: ${TIMESTAMP_ISO}
Contents:
  auth-users.dump  — pg_dump custom-format of auth.users (full table, DDL + rows)
  myfinance.dump   — pg_dump custom-format of the myfinance schema (DDL + rows)

Decryption (primary identity — Windows PC):
  age -d -i %USERPROFILE%\\.config\\myfinance-backup\\age-identity-primary.txt ^
      -o snapshot.tar ${TODAY}.tar.age

Restore order:
  1. pg_restore --data-only --table=users -Fc auth-users.dump  (load auth stub first)
  2. pg_restore -Fc myfinance.dump                             (FKs reference auth.users)

See scripts/backup/README.md §2.5.4 for full instructions.
EOF

log_info "Creating tar bundle"
tar -cf "${WORK_DIR}/snapshot.tar" -C "${WORK_DIR}" \
  auth-users.dump myfinance.dump README.txt

# ---------------------------------------------------------------------------
# 4.3.4 Single-recipient age encryption (v3 — Gate C cut B1)
# ---------------------------------------------------------------------------
log_info "Encrypting with primary recipient"
age \
  -r "$(cat "${RECIPIENTS_DIR}/primary.txt")" \
  -o "${WORK_DIR}/${TODAY}.tar.age" \
  "${WORK_DIR}/snapshot.tar"

# ---------------------------------------------------------------------------
# 4.3.5 Local SHA-256
# ---------------------------------------------------------------------------
log_info "Computing local SHA-256"
LOCAL_SHA256="$(sha256sum "${WORK_DIR}/${TODAY}.tar.age" | awk '{print $1}')"
log_info "Local SHA-256: ${LOCAL_SHA256}"

# ---------------------------------------------------------------------------
# 4.3.6 Upload to R2 daily/
# ---------------------------------------------------------------------------
log_info "Uploading to r2:${BUCKET}/daily/${TODAY}.tar.age"
rclone copy "${WORK_DIR}/${TODAY}.tar.age" "r2:${BUCKET}/daily/"
rclone check "${WORK_DIR}/${TODAY}.tar.age" "r2:${BUCKET}/daily/" --one-way

# ---------------------------------------------------------------------------
# 4.3.6b Post-upload SHA-256 re-verify (M12 fix)
# ---------------------------------------------------------------------------
log_info "Re-downloading for SHA-256 verification"
REDOWNLOAD_PATH="${WORK_DIR}/redownload-${TODAY}.tar.age"
rclone copy "r2:${BUCKET}/daily/${TODAY}.tar.age" "${WORK_DIR}/" --local-no-check-updated
mv "${WORK_DIR}/${TODAY}.tar.age" "${REDOWNLOAD_PATH}" 2>/dev/null || \
  rclone copy "r2:${BUCKET}/daily/${TODAY}.tar.age" "${WORK_DIR}/redownloaded/"

# Re-download to a distinct filename to avoid overwriting the original
REDOWNLOAD_DIR="${WORK_DIR}/r2-redownload"
mkdir -p "${REDOWNLOAD_DIR}"
rclone copy "r2:${BUCKET}/daily/${TODAY}.tar.age" "${REDOWNLOAD_DIR}/"
R2_SHA256="$(sha256sum "${REDOWNLOAD_DIR}/${TODAY}.tar.age" | awk '{print $1}')"

if [[ "$LOCAL_SHA256" != "$R2_SHA256" ]]; then
  log_error "SHA-256 mismatch: local=${LOCAL_SHA256} r2=${R2_SHA256} — quarantining"
  QUARANTINE_KEY="quarantine/$(date -u +%Y-%m-%dT%H-%M-%SZ)-daily-${TODAY}.tar.age"
  rclone moveto "r2:${BUCKET}/daily/${TODAY}.tar.age" "r2:${BUCKET}/${QUARANTINE_KEY}"
  dispatch_alert "MyFinance daily backup UPLOAD CORRUPTED" \
    "SHA-256 mismatch after upload. local=${LOCAL_SHA256} r2=${R2_SHA256} | Quarantined to: ${QUARANTINE_KEY}" || true
  exit 7
fi
log_info "SHA-256 re-verify passed: ${R2_SHA256}"

# ---------------------------------------------------------------------------
# 4.3.7 Weekly promotion (Sundays)
# ---------------------------------------------------------------------------
DAY_OF_WEEK="$(date -u +%u)"  # 1=Mon … 7=Sun
if [[ "$DAY_OF_WEEK" -eq 7 ]]; then
  WEEK="$(date -u +%Y-W%V)"
  log_info "Sunday: promoting to weekly/${WEEK}.tar.age"
  rclone copyto "r2:${BUCKET}/daily/${TODAY}.tar.age" "r2:${BUCKET}/weekly/${WEEK}.tar.age"
fi

# ---------------------------------------------------------------------------
# 4.3.8 Monthly promotion (1st of month)
# ---------------------------------------------------------------------------
DAY_OF_MONTH="$(date -u +%d)"
if [[ "$DAY_OF_MONTH" -eq 1 ]]; then
  MONTH="$(date -u +%Y-%m)"
  log_info "1st of month: promoting to monthly/${MONTH}.tar.age"
  rclone copyto "r2:${BUCKET}/daily/${TODAY}.tar.age" "r2:${BUCKET}/monthly/${MONTH}.tar.age"
fi

# ---------------------------------------------------------------------------
# 4.3.9 Chained restore-verify
# ---------------------------------------------------------------------------
log_info "Chaining verify-restore for daily/${TODAY}.tar.age"
VERIFY_SCRIPT="${SCRIPT_DIR}/verify-restore.sh"

# Pass identity to verify via stdin (B7 fix).
# IDENTITY_CONTENT was slurped from stdin at script startup (top of file).
# Pipe it into verify-restore.sh so that script can write it to its tmpfs.

VERIFY_FAILED=false
if ! printf '%s' "${IDENTITY_CONTENT}" | \
     bash "${VERIFY_SCRIPT}" --target "daily/${TODAY}.tar.age"; then
  VERIFY_FAILED=true
fi

if [[ "$VERIFY_FAILED" == "true" ]]; then
  log_error "Chained verify FAILED — quarantining daily/${TODAY}.tar.age"
  QUARANTINE_KEY="quarantine/$(date -u +%Y-%m-%dT%H-%M-%SZ)-daily-${TODAY}.tar.age"
  rclone moveto "r2:${BUCKET}/daily/${TODAY}.tar.age" "r2:${BUCKET}/${QUARANTINE_KEY}" || true
  # last-verify.json was already written by verify-restore.sh with the failing probes
  LOG_TAIL="$(tail -20 "${LOG_FILE}" 2>/dev/null || true)"
  dispatch_alert "MyFinance daily backup VERIFY FAILED" \
    "Artefact quarantined to: ${QUARANTINE_KEY}\n${LOG_TAIL}" || true
  exit 8
fi

# ---------------------------------------------------------------------------
# 4.3.10 Upsert status/last-success.json (only on full success)
# ---------------------------------------------------------------------------
ELAPSED_MS=$(( ( $(date +%s) - RUN_START ) * 1000 ))

# Detect same-day quarantine events (M10 fix)
PREV_FAILED_ATTEMPTS=0
QUARANTINED_ARTEFACTS="[]"
SAME_DAY_QUARANTINE="$(rclone lsf "r2:${BUCKET}/quarantine/" 2>/dev/null | grep "^$(date -u +%Y-%m-%d)-daily-" || true)"
if [[ -n "$SAME_DAY_QUARANTINE" ]]; then
  PREV_FAILED_ATTEMPTS="$(printf '%s\n' "$SAME_DAY_QUARANTINE" | wc -l | tr -d '[:space:]')"
  QUARANTINED_ARTEFACTS="[$(printf '%s\n' "$SAME_DAY_QUARANTINE" | \
    sed 's/^/\"quarantine\//;s/$/\"/' | paste -sd ',' -)]"
fi

SUCCESS_JSON="$(cat <<EOF
{
  "path": "daily/${TODAY}.tar.age",
  "size": $(stat -c%s "${WORK_DIR}/${TODAY}.tar.age" 2>/dev/null || echo 0),
  "sha256": "${LOCAL_SHA256}",
  "timestamp": "${TIMESTAMP_ISO}",
  "durationMs": ${ELAPSED_MS},
  "previousFailedAttempts": ${PREV_FAILED_ATTEMPTS},
  "quarantinedArtefacts": ${QUARANTINED_ARTEFACTS}
}
EOF
)"
printf '%s' "${SUCCESS_JSON}" | rclone rcat "r2:${BUCKET}/status/last-success.json"

# ---------------------------------------------------------------------------
# 4.3.11 Append to status/status.log on R2
# ---------------------------------------------------------------------------
STATUS_LOG_LINE="$(date -u +%Y-%m-%dT%H:%M:%SZ) daily SUCCESS sha256=${LOCAL_SHA256} durationMs=${ELAPSED_MS}"
{
  rclone cat "r2:${BUCKET}/status/status.log" 2>/dev/null || true
  printf '%s\n' "${STATUS_LOG_LINE}"
} | rclone rcat "r2:${BUCKET}/status/status.log"

# ---------------------------------------------------------------------------
# 4.3.12 Kuma success ping (fire-and-forget — NO -f flag)
# ---------------------------------------------------------------------------
KUMA_URL="${MYFINANCE_BACKUP_KUMA_PUSH_URL:-}"
if [[ -n "$KUMA_URL" ]]; then
  curl -sS --max-time 10 "${KUMA_URL}?status=up&msg=ok&ping=${ELAPSED_MS}" \
    > /dev/null 2>&1 || log_info "kuma push failed (non-fatal)"
fi

# ---------------------------------------------------------------------------
# 4.3.13 (deferred under v3 — Gate C cut M1: off-VPS dead-man-switch out of scope
# until/unless Kuma proves insufficient. Re-introduce here if a bounded follow-up
# adds healthchecks.io.)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 4.3.14 Cleanup + emit final JSON
# ---------------------------------------------------------------------------
log_info "=== backup-daily.sh SUCCESS ==="
emit_json_result "{\"path\":\"daily/${TODAY}.tar.age\",\"sha256\":\"${LOCAL_SHA256}\",\"durationMs\":${ELAPSED_MS}}"

# ---------------------------------------------------------------------------
# Failure handler (sourced helpers + set -e will call this via EXIT trap on error)
# ---------------------------------------------------------------------------
# Note: this function is defined but EXIT trap above (cleanup) runs on all exits.
# Alert dispatch for failures is handled inline at each failure point above.
# The trap only cleans up the working directory.
