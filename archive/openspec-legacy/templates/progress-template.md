# progress-template.md — copied into every new openspec/changes/<id>/ directory
# as progress.md by /opsx:propose (or by the operator with Copy-Item if the
# upstream skill does not yet support it). The file is YAML — no surrounding
# prose, no markdown headings. A ConvertFrom-Yaml (PowerShell 7) or
# yaml.safe_load (Python) MUST be able to parse it in one call without regex
# gymnastics. Use `#` for comments throughout (HTML <!-- --> blocks break the
# YAML parser; addressed in adversarial-review Finding #3, 2026-05-27).
#
# Schema (v1):
#   - 6 required keys, listed below.
#   - Optional keys MAY be added without violating the contract.
#   - The backend-developer subagent REWRITES this file at the end of every
#     closed task in tasks.md; see .claude/agents/backend-developer.md and
#     design.md Decision 2.

# current_task (REQUIRED, string)
# Format: "<section>.<number>" matching a checkbox ID in tasks.md
# (e.g. "3.2"). Use the literal string `none` when no task is in flight yet
# (fresh change after /opsx:propose) or when all tasks are closed.
current_task: none

# last_completed (REQUIRED, string)
# Format: same as current_task. Use the literal string `none` if no task has
# been closed yet on this change.
last_completed: none

# next_step (REQUIRED, string)
# Free-form one-line description of the concrete next action the next session
# should take. Keep it under ~120 chars. Default for a fresh change:
# "ready for /opsx:apply".
next_step: "ready for /opsx:apply"

# decisions_pending_design_update (REQUIRED, list of strings, may be empty)
# Each entry is a one-line description of a mid-flight decision that diverged
# from design.md (e.g. swapped `rclone` for `aws-cli`). The next /opsx:archive
# or /openspec-sync-specs invocation reads this list to reconcile design.md
# with what actually happened.
decisions_pending_design_update: []

# blockers (REQUIRED, list of strings, may be empty)
# Each entry is a one-line description of a blocker preventing forward
# progress (e.g. "waiting on operator to install rclone"). Empty list means
# the next session can proceed without external action.
blockers: []

# last_updated (REQUIRED, string)
# ISO-8601 UTC timestamp (format: YYYY-MM-DDTHH:MM:SSZ). Refreshed by the
# backend-developer subagent at the end of every closed task. Used by
# /opsx:apply's "resuming from" summary to flag staleness against the most
# recent commit on the change branch.
last_updated: "<fill at copy time — YYYY-MM-DDTHH:MM:SSZ>"
