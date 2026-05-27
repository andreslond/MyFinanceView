## 1. Template and per-change scaffold

- [x] 1.1 Create `openspec/templates/` directory (it does not exist yet — confirm with `Test-Path openspec/templates`)
- [x] 1.2 Write `openspec/templates/progress-template.md` as the YAML skeleton with all six required keys (`current_task`, `last_completed`, `next_step`, `decisions_pending_design_update`, `blockers`, `last_updated`), each preceded by an HTML-style inline comment documenting purpose, expected format, and "required vs optional". Placeholder values: `current_task: none`, `last_completed: none`, `next_step: "ready for /opsx:apply"`, empty lists, `last_updated: <fill at copy time>`.
- [x] 1.3 Smoke-test the template by manually copying it into `openspec/changes/harness-progress-tracking/progress.md` (this very change — eat our own dogfood) with `last_updated` set to the current ISO-8601 UTC timestamp and `current_task: 1.3` (the task being completed). Commit.
- [x] 1.4 Backfill `progress.md` into the in-flight `openspec/changes/supabase-backup-policy/progress.md` from the template; populate `last_completed` from the latest ticked checkbox in its `tasks.md`, `current_task` from the first unticked checkbox, `next_step` from a one-line description of that task. Commit on the harness branch (the supabase-cuts worktree will see the file once the branches converge — operator decision whether to cherry-pick or wait for merge).

## 2. Preflight script

- [x] 2.1 Write `scripts/preflight.ps1` shebang-equivalent header `#requires -Version 5.1` (works on stock Win10 PS 5.1 + PS 7); use `[CmdletBinding()]` with no params for v1
- [x] 2.2 Implement `Write-Check` helper: takes `-Tag` (`OK`|`WARN`|`FAIL`|`SKIP`), `-Label`, `-Detail`; formats as `[TAG]    <label> — <detail>` with fixed-width tag column (8 chars) for visual alignment; tracks a script-scope `$script:FailCount` that increments on `FAIL` only (NOT on `WARN`)
- [x] 2.3 Implement check: active-changes count under `openspec/changes/` excluding `archive/`; `[OK]` if count == 1, `[WARN] N active changes — confirm intentional parallel work` if > 1, `[OK] no active changes` if == 0
- [x] 2.4 Implement check: `./mvnw -q compile` exit status; capture stderr tail (last 5 lines) on failure; emit `[OK] mvn compile` or `[FAIL] mvn compile failed — <stderr tail>`. **Honour the project rule from `base-standards.md` §5:** this is a fast `compile` check, NOT `verify` (which runs tests against Testcontainers Postgres and is slow); the operator runs the full `verify` manually before opening a PR
- [x] 2.5 Implement check: working tree cleanliness via `git status --porcelain`; `[OK] working tree clean` if empty, `[WARN] N modified/untracked files — <list first 3>` if not (`[WARN]` not `[FAIL]` because in-progress work is the normal case)
- [x] 2.6 Implement check: current branch + last commit; `git branch --show-current` + `git log -1 --format='%h %s'`; always `[OK]` (informational)
- [x] 2.7 Implement check: per-active-change presence of `proposal.md`, `design.md`, `tasks.md`, `progress.md`; `[OK] <change> artefacts complete` if all four present, `[FAIL] <change> missing: <list>` if any missing
- [x] 2.8 Implement check: last verified Supabase backup timestamp; if `rclone` is not on PATH (test via `Get-Command rclone -ErrorAction SilentlyContinue`) emit `[SKIP] rclone not installed — backup freshness check skipped (install rclone to enable)` and continue; if available, run `rclone cat r2:my-finance-view-backups/status/last-success.json 2>$null | ConvertFrom-Json` and emit `[OK] last backup <X> hours ago` or `[WARN] last backup <X> hours ago — > 24h` (FAIL only if R2 unreachable AND rclone present — that's a real anomaly)
- [x] 2.9 At end-of-script, print a single summary line `=== preflight: <passes> passes, <warns> warns, <skips> skips, <fails> fails ===` and `exit $script:FailCount` (so exit 0 iff zero FAILs, exit equal to fail count otherwise)
- [ ] 2.10 Smoke-test the script on the operator's machine in PS 5.1: `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1`; verify all sections emit a line, exit code matches (`$LASTEXITCODE` after the call) (OPERATOR ACTION — see notes/operator-handoff.md)
- [ ] 2.11 Smoke-test the script in `pwsh` 7: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1`; same expectations (OPERATOR ACTION — see notes/operator-handoff.md)
- [ ] 2.12 Smoke-test failure mode: temporarily break the project's compile (rename a Java import to nonsense), re-run preflight, confirm `[FAIL] mvn compile failed` AND exit code is non-zero AND the script still ran the remaining checks (not aborted mid-script); restore the file (OPERATOR ACTION — see notes/operator-handoff.md)
- [ ] 2.13 Smoke-test missing-rclone mode: temporarily rename `rclone.exe` (or shadow it via a session-scoped `$env:Path` edit), re-run preflight, confirm `[SKIP] rclone not installed` AND the script still exits based on real failures (not the SKIP); restore PATH (OPERATOR ACTION — see notes/operator-handoff.md)
- [ ] 2.14 Smoke-test two-active-changes mode: with `supabase-backup-policy` and `harness-progress-tracking` both present, confirm the active-changes line is `[WARN]` and the script still exits 0 (OPERATOR ACTION — see notes/operator-handoff.md)

## 3. SessionStart hook

- [x] 3.1 Read existing `.claude/settings.json` to capture any current `hooks.SessionStart` entries; the new entry MUST be additive (append, not replace)
- [x] 3.2 Add a `hooks.SessionStart` entry with `type: "command"` and command per Decision 5: `pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1 2>nul || powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\\preflight.ps1`. If `.claude/settings.json` does not exist yet, create it with the minimal `{"hooks":{"SessionStart":[…]}}` envelope.
- [ ] 3.3 Open a fresh Claude Code session in this repo; confirm preflight output appears in the first conversation turn as additional context (visible in the session log if not in chat); the operator manually verifies this and ticks the box (OPERATOR ACTION — see notes/operator-handoff.md)
- [ ] 3.4 Smoke-test the fallback path: temporarily rename `pwsh.exe` (or remove it from `$env:Path`), open a fresh session, confirm `powershell.exe` ran the script; restore PATH (OPERATOR ACTION — see notes/operator-handoff.md)
- [x] 3.5 Document the hook entry's purpose in a short comment on the JSON above it (JSONC is fine if `.claude/settings.json` allows it; otherwise inline string note)

## 4. Workflow and agent updates

- [ ] 4.1 Update `docs/workflow.md` Phase D ("Implementation"): add a sub-step at the top — "Phase D begins with reading `openspec/changes/<id>/progress.md` and posting a one-paragraph 'resuming from' summary citing `current_task`, `next_step`, and any non-empty `decisions_pending_design_update`/`blockers`. If `progress.md.last_updated` is older than the most recent commit on the change branch, the summary MUST flag the staleness explicitly." Cross-reference Decision 2 of this change's design.md.
- [ ] 4.2 Update `docs/workflow.md` "Session start" preamble to reference the SessionStart hook output and `scripts/preflight.ps1` as the source of repo-state context — operators reading the doc for the first time should understand the hook is part of the standard environment.
- [ ] 4.3 Update `.claude/agents/backend-developer.md` with: "After closing every task in `tasks.md` (i.e. flipping `- [ ]` to `- [x]`), rewrite `openspec/changes/<id>/progress.md` per the schema in `openspec/templates/progress-template.md`. Update `last_completed` to the just-closed task ID, set `current_task` to the next pending task ID (or `none` if all closed), set `next_step` to one line describing that task's first action, refresh `last_updated`, and append to `decisions_pending_design_update` or `blockers` if anything new surfaced. Do NOT update after every tool call — per-task cadence only." Cross-reference design Decision 2.
- [ ] 4.4 Update `.claude/agents/adversarial-reviewer.md` to add `progress.md` to its review checklist: a `progress.md.last_updated` older than the most recent commit on the change branch SHALL be reported as a Minor finding with category `process-tooling`. (Design Open Question 4 resolved: yes, include this.)
- [ ] 4.5 Update `CLAUDE.md` workflow section with a one-line pointer: "Sessions start with `scripts/preflight.ps1` output (via SessionStart hook) — read it first. Active changes carry a live `progress.md`."

## 5. Propose-skill template seeding (resolve Open Question 1)

- [ ] 5.1 Inspect the `openspec-propose` skill (`/opsx:propose`) instructions and the `openspec new change` CLI behaviour — determine whether it supports a post-create template copy step or whether the operator must manually `Copy-Item` after each new change
- [ ] 5.2 If the skill supports a template hook: patch the skill (or its config) to copy `openspec/templates/progress-template.md` into the new change directory as `progress.md` with `last_updated` set to the propose-time UTC timestamp
- [ ] 5.3 If the skill does NOT support a template hook: document a manual step in `docs/workflow.md` Phase B (propose) — operator runs `Copy-Item openspec/templates/progress-template.md openspec/changes/<id>/progress.md` immediately after `/opsx:propose` returns; AND file a follow-up TODO in `docs/workflow.md` to revisit when the upstream skill supports it
- [ ] 5.4 Smoke-test the chosen path on a throwaway change (`openspec new change throwaway-test` followed by the copy step, then `openspec validate throwaway-test --strict`, then delete the change directory)

## 6. Validation, dogfooding, and archive

- [ ] 6.1 Run `openspec validate harness-progress-tracking --strict` from the repo root; fix any structural complaints until it passes clean
- [ ] 6.2 Run the `adversarial-review` skill against this change (Gate D); address any Blocker or Major findings — applying Gate C threat-model triage per the proposal: this change has no security-sensitive adversary, so findings about "what if the script is malicious" are auto-rejected
- [ ] 6.3 With everything ticked above, the next session opened in this repo MUST visibly run preflight (Section 3) AND `/opsx:apply` against any change MUST visibly read `progress.md` (Section 4.1). Operator confirms both before archive.
- [ ] 6.4 Update `proposal.md` with a note confirming preflight is live, `progress.md` template is in place, and the SessionStart hook is wired
- [ ] 6.5 `/opsx:archive harness-progress-tracking` and `/openspec-sync-specs` to merge the delta into `openspec/specs/harness-progress-tracking/`
- [ ] 6.6 Update `SPEC.md` (or `docs/base-standards.md` §3 — whichever is more discoverable) with a short pointer that the workflow now expects `progress.md` per change + preflight at session start
- [ ] 6.7 Notify the Notion project page that this épica is closed and v2 (CHECKPOINTS.md + leader-mode) is the next harness piece if v1 proves useful
