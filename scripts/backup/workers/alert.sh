#!/usr/bin/env bash
# alert.sh — dispatch an alert through ntfy.sh (push) and Resend HTTP API (email).
# Exports: dispatch_alert <title> <body>
# Returns: 0 if at least one channel succeeded; 1 if both failed.
#
# Source this file from other workers to get dispatch_alert:
#   source "$(dirname "$0")/alert.sh"
#
# v1 (operator decision 2026-06-01): Resend HTTP API replaces Gmail SMTP for
# production hygiene. Round-4 adversarial review (2026-06-02) caught a
# subshell-scoping bug in the prior parallel-fan-out implementation; this
# version captures background PIDs and waits on each individually so the
# at-least-one-succeeds contract is honoured.
set -euo pipefail
IFS=$'\n\t'

source "$(dirname "$0")/_common.sh"

# ---------------------------------------------------------------------------
# dispatch_alert <title> <body>
# Fires ntfy POST and Resend HTTP POST in parallel, then waits on each PID
# separately so exit codes propagate to the parent shell (subshell-scoping
# fix from round-4 adversarial review). Returns 0 if at least one channel
# succeeded, 1 otherwise.
# ---------------------------------------------------------------------------
dispatch_alert() {
  local title="$1"
  local body="$2"

  # m2 fix: strip CRLF from title to prevent header injection on the ntfy leg.
  # Collapse LF to single space so the header stays single-line yet the
  # message remains intelligible (the body field carries the multi-line log).
  title="${title//$'\r'/}"
  title="${title//$'\n'/ }"

  # --- ntfy.sh push --------------------------------------------------------
  send_ntfy() {
    local topic="${MYFINANCE_BACKUP_NTFY_TOPIC:-}"
    if [[ -z "$topic" ]]; then
      log_error "MYFINANCE_BACKUP_NTFY_TOPIC not set — skipping ntfy alert"
      return 1
    fi
    # m1 fix: sanity-check the topic shape so a misconfigured value cannot
    # produce a malformed URL or post to a different topic.
    if [[ ! "$topic" =~ ^[A-Za-z0-9_-]{16,}$ ]]; then
      log_error "MYFINANCE_BACKUP_NTFY_TOPIC fails sanity regex ^[A-Za-z0-9_-]{16,}\$ — skipping ntfy alert"
      return 1
    fi
    curl -fsSL --max-time 15 \
      -X POST "https://ntfy.sh/${topic}" \
      -H "Title: ${title}" \
      -d "${body}" \
      > /dev/null 2>&1
  }

  # --- Resend transactional email via HTTP API ------------------------------
  # Builds the request body via jq so title/body are JSON-safe regardless of
  # quoting, newlines, or backslashes. Requires `jq` in the runner image
  # (Dockerfile.runner installs it).
  send_resend() {
    local api_key="${MYFINANCE_BACKUP_RESEND_API_KEY:-}"
    local from_addr="${MYFINANCE_BACKUP_ALERT_FROM:-}"
    local to_addr="${MYFINANCE_BACKUP_ALERT_TO:-}"
    if [[ -z "$api_key" ]]; then
      log_error "MYFINANCE_BACKUP_RESEND_API_KEY not set — skipping Resend alert"
      return 1
    fi
    if [[ -z "$from_addr" || -z "$to_addr" ]]; then
      log_error "MYFINANCE_BACKUP_ALERT_FROM or MYFINANCE_BACKUP_ALERT_TO not set — skipping Resend alert"
      return 1
    fi
    local payload
    payload="$(jq -nc \
      --arg from "$from_addr" \
      --arg to "$to_addr" \
      --arg subject "$title" \
      --arg text "$body" \
      '{from: $from, to: $to, subject: $subject, text: $text}')"

    curl -fsSL --max-time 30 \
      -X POST "https://api.resend.com/emails" \
      -H "Authorization: Bearer ${api_key}" \
      -H "Content-Type: application/json" \
      -d "$payload" \
      > /dev/null 2>&1
  }

  # ----- Parallel fan-out with PID-tracked exit codes (B1 fix) --------------
  # Each function runs in a background subshell. We capture the PIDs and
  # `wait <pid>` on each separately; the exit status of the second-argument
  # form of `wait` IS the exit status of the named child, which makes the
  # at-least-one-succeeds contract honest. The previous design used
  # `... && var=1 &` which assigned `var` in the child shell and never
  # propagated to the parent — caught by round-4 adversarial review.
  local ntfy_ok=0
  local resend_ok=0

  send_ntfy &
  local ntfy_pid=$!
  send_resend &
  local resend_pid=$!

  if wait "$ntfy_pid"; then
    ntfy_ok=1
  fi
  if wait "$resend_pid"; then
    resend_ok=1
  fi

  if [[ $ntfy_ok -eq 0 && $resend_ok -eq 0 ]]; then
    log_error "All alert channels failed for: ${title}"
    return 1
  fi

  log_info "Alert dispatched: ${title} (ntfy_ok=${ntfy_ok} resend_ok=${resend_ok})"
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
