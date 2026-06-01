#!/usr/bin/env bash
# alert.sh — dispatch an alert through ntfy.sh (push) and Gmail SMTP (email).
# Exports: dispatch_alert <title> <body>
# Returns: 0 if at least one channel succeeded; 1 if both failed.
#
# Source this file from other workers to get dispatch_alert:
#   source "$(dirname "$0")/alert.sh"
set -euo pipefail
IFS=$'\n\t'

source "$(dirname "$0")/_common.sh"

# ---------------------------------------------------------------------------
# dispatch_alert <title> <body>
# Fires ntfy POST and Gmail SMTP send in parallel via & + wait.
# Marks each channel as succeeded/failed independently.
# Returns 0 if at least one channel succeeded, 1 otherwise.
# ---------------------------------------------------------------------------
dispatch_alert() {
  local title="$1"
  local body="$2"

  local ntfy_ok=0
  local gmail_ok=0

  # --- ntfy.sh push --------------------------------------------------------
  send_ntfy() {
    local topic="${MYFINANCE_BACKUP_NTFY_TOPIC:-}"
    if [[ -z "$topic" ]]; then
      log_error "MYFINANCE_BACKUP_NTFY_TOPIC not set — skipping ntfy alert"
      return 1
    fi
    curl -fsSL --max-time 15 \
      -X POST "https://ntfy.sh/${topic}" \
      -H "Title: ${title}" \
      -d "${body}" \
      > /dev/null 2>&1
  }

  # --- Gmail SMTP via curl --------------------------------------------------
  # Uses curl's SMTP support with STARTTLS. The App Password is read from env.
  send_gmail() {
    local gmail_pass="${MYFINANCE_BACKUP_GMAIL_APP_PASSWORD:-}"
    local to="aftorresl01@gmail.com"
    if [[ -z "$gmail_pass" ]]; then
      log_error "MYFINANCE_BACKUP_GMAIL_APP_PASSWORD not set — skipping Gmail alert"
      return 1
    fi
    # Build RFC 2822 message in a temp file
    local msg_file
    msg_file="$(mktemp)"
    trap 'rm -f "$msg_file"' RETURN
    printf 'From: MyFinance Backup <aftorresl01@gmail.com>\r\nTo: %s\r\nSubject: %s\r\n\r\n%s\r\n' \
      "$to" "$title" "$body" > "$msg_file"

    curl -fsSL --max-time 30 \
      --url "smtp://smtp.gmail.com:587" \
      --ssl-reqd \
      --user "aftorresl01@gmail.com:${gmail_pass}" \
      --mail-from "aftorresl01@gmail.com" \
      --mail-rcpt "$to" \
      --upload-file "$msg_file" \
      > /dev/null 2>&1
  }

  # Fire both in parallel
  send_ntfy   && ntfy_ok=1  || true &
  send_gmail  && gmail_ok=1 || true &
  wait

  if [[ $ntfy_ok -eq 0 && $gmail_ok -eq 0 ]]; then
    log_error "All alert channels failed for: ${title}"
    return 1
  fi

  log_info "Alert dispatched: ${title} (ntfy_ok=${ntfy_ok} gmail_ok=${gmail_ok})"
  return 0
}

# Allow direct invocation for testing: alert.sh "My Title" "My body"
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [[ $# -lt 2 ]]; then
    printf 'Usage: %s <title> <body>\n' "$0" >&2
    exit 1
  fi
  dispatch_alert "$1" "$2"
fi
