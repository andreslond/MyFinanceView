#!/usr/bin/env bash
# _common.sh — shared helpers sourced by every backup worker.
# Source this file at the TOP of each worker (after the shebang line):
#   source "$(dirname "$0")/_common.sh"
set -euo pipefail
IFS=$'\n\t'

# ---------------------------------------------------------------------------
# Logging helpers
# ---------------------------------------------------------------------------

# log_info <message>  — write a timestamped INFO line to stderr
log_info() {
  printf '[%s] INFO  %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

# log_error <message>  — write a timestamped ERROR line to stderr
log_error() {
  printf '[%s] ERROR %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >&2
}

# ---------------------------------------------------------------------------
# JSON result emitter (final stdout line consumed by workers.js)
# ---------------------------------------------------------------------------

# emit_json_result <json-string>
# Prints a single JSON line to stdout. Workers.js reads the LAST stdout line
# and parses it; calling this at the very end of a successful run is mandatory.
# Do NOT call this before a failure — workers.js uses the exit code to distinguish.
emit_json_result() {
  printf '%s\n' "$1"
}

# ---------------------------------------------------------------------------
# R2 helpers
# ---------------------------------------------------------------------------

# rclone_target <path>  — expand to the full r2 remote path
rclone_target() {
  local bucket="${BACKUP_R2_BUCKET:-my-finance-view-backups}"
  printf 'r2:%s/%s' "$bucket" "$1"
}

# ---------------------------------------------------------------------------
# Postgres connection string builder
# ---------------------------------------------------------------------------

# pg_dsn  — returns the connection URL for pg_dump / pg_restore / psql
pg_dsn() {
  printf 'postgresql://%s:%s@%s:%s/%s?sslmode=require' \
    "${BACKUP_DB_USER}" \
    "${BACKUP_DB_PASSWORD}" \
    "${BACKUP_DB_HOST}" \
    "${BACKUP_DB_PORT:-5432}" \
    "${BACKUP_DB_NAME:-postgres}"
}
