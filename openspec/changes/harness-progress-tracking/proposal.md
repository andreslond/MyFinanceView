## Why

Long-running `/opsx:apply` sessions for this project routinely outlive a single Claude Code context window — the in-flight `supabase-backup-policy` change hit compaction mid-implementation, and recovering "where was I?" relied on the conversation summary rather than on a structured artefact the next session could read. Equally, every session today starts blind to repo state — number of active changes, build health, working tree cleanliness, last verified Supabase backup timestamp — because nothing runs before the operator's first message. Both gaps cost real minutes per session and create the risk of writing on top of stale assumptions (the exact failure the `[[supabase-production-data]]` memory exists to prevent).

The pattern is borrowed from `betta-tech/ejemplo-harness-subagentes` (init.sh + `progress/current.md` + CHECKPOINTS.md), adapted to OpenSpec's per-change directory rather than a central `feature_list.json`. v1 takes the two highest-value pieces — per-change progress file and pre-session state report — and defers checkpoints + strict leader-mode to follow-up changes once v1 proves out.

## What Changes

- **New artefact `progress.md` inside every active change directory.** YAML-shaped, written by the `backend-developer` subagent at the end of every closed task, read by the main thread at the start of every `/opsx:apply` session. Schema: `current_task`, `last_completed`, `next_step`, `decisions_pending_design_update`, `blockers`, `last_updated`. Archived to `openspec/archive/<id>/progress.md` alongside the rest of the change at `/opsx:archive` time.
- **New `scripts/preflight.ps1` script** (PowerShell — primary OS is Windows 10 per the environment) that the agent invokes before non-trivial work. Reports: active-change count under `openspec/changes/` (warns if > 1), `./mvnw -q compile` exit status, working tree cleanliness, current branch + last commit hash + subject, per-active-change presence of `proposal.md`/`design.md`/`tasks.md`/`progress.md`, and the timestamp of the last verified Supabase backup (read from R2 `status/last-success.json` via `rclone lsf`, gracefully skipped if `rclone` is not installed locally — the script is operator-facing on Windows, not VPS-facing). Exit 0 = green; exit ≠ 0 with `[FAIL]` lines surfaces blocker conditions.
- **New directive in `CLAUDE.md` instructing the agent to invoke preflight before any non-trivial work** (any `/opsx:apply`, any code edit, any architectural decision, any commit). Trivial conversational sessions (questions, exploration) may skip preflight — agent's judgment call. **No SessionStart hook in `.claude/settings.json`** — an earlier draft of this change installed such a hook and was reverted after operator dogfooding revealed (a) every-session overhead taxed trivial sessions unfairly, (b) hook output is Claude-only context (invisible to operator in the terminal), and (c) cross-shell escape bugs (Git Bash backslash interpretation, cmd-vs-Bash redirect syntax) added unnecessary fragility. Agent-invoked matches the `betta-tech/ejemplo-harness-subagentes` reference pattern (`init.sh` from `AGENTS.md`). See `design.md` Decision 5 v2 for the rollback rationale.
- **`/opsx:apply` workflow update** (`docs/workflow.md` Phase D): the phase MUST begin by reading the change's `progress.md` and posting a one-paragraph "resuming from" summary BEFORE invoking the `backend-developer` subagent. The `backend-developer` subagent MUST update `progress.md` at the end of every closed task (NOT after every tool call — that frequency was rejected as overhead during Gate A).
- **`backend-developer` agent definition update** (`.claude/agents/backend-developer.md`): adds the `progress.md` write directive with the per-task cadence and the YAML schema reference.
- **Template `openspec/templates/progress-template.md`** seeded into every new change by `/opsx:propose` (a small addition to the propose skill's create-artefacts loop — documented in the `openspec-workflow` capability's modified delta).

## Capabilities

### New Capabilities
- `harness-progress-tracking`: Per-change live implementation state (`progress.md`) and pre-session repo-state report (`preflight.ps1` + SessionStart hook) — together they let a session that did not start the change pick it up coherently and let any session refuse to start work on top of an unhealthy repo.

### Modified Capabilities
<!-- No existing OpenSpec capability spec changes its REQUIREMENTS. Updates to
     docs/workflow.md, .claude/agents/backend-developer.md, and the propose
     skill are workflow + tooling changes, not spec-level behaviour changes.
     The only canonical capability today is `backend-runtime` and it is
     unaffected (no Spring code changes). -->

## Impact

- **New files (committed):**
  - `openspec/templates/progress-template.md` — the YAML skeleton copied into every new change
  - `scripts/preflight.ps1` — the agent-invoked repo-state report
  - `openspec/specs/harness-progress-tracking/spec.md` — created at archive time by `/openspec-sync-specs` (the delta lives under this change's `specs/` until then)
- **Modified files (committed):**
  - `docs/workflow.md` — Phase D / Gate D mandates progress.md read/write; `## 0. Session start` section describes agent-invoked preflight (NOT a hook)
  - `.claude/agents/backend-developer.md` — adds the progress.md update directive, the YAML schema reference, AND the preflight-before-non-trivial-work directive (mirrors CLAUDE.md so the subagent has it locally)
  - `.claude/agents/adversarial-reviewer.md` — adds `progress.md` staleness check + missing-preflight-evidence check as Minor `process-tooling` findings
  - `CLAUDE.md` — carries the canonical preflight directive in the workflow section (this IS the primary implementation of Decision 5 v2 — no hook, just an instruction the agent reads at session start)
- **Files explicitly NOT modified (rollback under Decision 5 v2):** `.claude/settings.json` carries NO hook entry from this change. A previous draft added one; the rollback commit removes it. Operators who locally added other hooks to `.claude/settings.json` are unaffected.
- **Backend code:** none. No Spring Boot changes, no jOOQ changes, no schema changes, no Flyway migrations, no Supabase writes. This change is process tooling — every modified file is documentation, configuration, or PowerShell.
- **Dependencies:**
  - Adds an optional runtime dependency on `pwsh` (PowerShell 7+) for the hook. Windows PowerShell 5.1 (already installed on Windows 10) is acceptable as a fallback if `pwsh` is not on PATH — the hook's command resolution handles both.
  - Optional runtime dependency on `rclone` for the Supabase-backup-freshness probe; gracefully skipped with a `[SKIP]` line when absent.
- **Risks:**
  - **Preflight overhead during real-work sessions.** A verbose preflight report adds tokens to every conversation that invokes it. Mitigation: terse default output (one line per check, [OK]/[FAIL]/[WARN]/[SKIP] tags). Under Decision 5 v2 (agent-invoked, not hooked), trivial sessions skip preflight entirely — zero token cost.
  - **Agent forgets to invoke preflight before non-trivial work.** Mitigation: CLAUDE.md carries the directive in the workflow section; `backend-developer.md` mirrors it; `adversarial-reviewer.md` flags missing-preflight evidence as a Minor `process-tooling` finding. Recovery cost is low — agent can run preflight at any later point in the session and recover.
  - **`progress.md` drift.** The subagent might forget to update it after a task; the file silently goes stale. Mitigation: `tasks.md` checkboxes are the source of truth for "what's done"; `progress.md` is the source of truth for "what to do next + decisions made mid-flight not yet in design.md". `adversarial-review` is briefed to flag a stale `progress.md` (last_updated older than the most recent commit on the change branch) as a Minor finding.
  - **Cross-session race.** Two sessions touching the same change concurrently produce conflicting writes to `progress.md`. Mitigation: git's merge-conflict surface is acceptable for v1 — single-operator project, two parallel sessions on one change is itself a process smell.
- **Threat model:** this change is process tooling, not security-sensitive. The artefacts read-only the local repo and (optionally) the R2 backup-status object. They do NOT write to Supabase, the application database, or any production system. No adversary model is needed; out-of-scope-adversary findings during adversarial review (e.g. "preflight could be spoofed by a malicious local script") are auto-rejected per the same Gate C policy that governs every change.
- **Notion:** new TASK-HARNESS-PROGRESS entry on the project page; cross-link to the modified workflow.md sections.
- **Unblocks:** future OpenSpec changes will start with a green preflight check (agent-invoked per CLAUDE.md directive) and a populated `progress.md`, materially reducing "wait, where was I?" friction. Specifically unblocks the eventual `harness-checkpoints` follow-up (out of scope here) which depends on having a working preflight script and a stable per-change progress file pattern to layer additional invariants on top.

---

## Closing note — 2026-05-27 (archive-time confirmation)

This change is **live** as of the archive commit. Concretely:

- `scripts/preflight.ps1` is committed and self-tested (4 OK / 2 WARN / 1 SKIP / 0 FAIL on the operator's PS 5.1).
- `openspec/templates/progress-template.md` is committed; both active changes (`harness-progress-tracking`, `supabase-backup-policy`) carry a populated `progress.md` at archive time.
- The canonical preflight directive lives in `CLAUDE.md ## Workflow` and is mirrored into `.claude/agents/backend-developer.md` item 7.
- `.claude/agents/adversarial-reviewer.md` checks for stale `progress.md` AND missing-preflight evidence as Minor `process-tooling` findings.
- `docs/workflow.md §0 Session start` describes the agent-invoked pattern.
- `docs/base-standards.md §3` cross-links to the harness expectations.
- `.claude/commands/opsx/propose.md` + `.claude/skills/openspec-propose/SKILL.md` seed `progress.md` from the template at change-creation time; a manual `Copy-Item` fallback is documented in `docs/workflow.md` Phase B.

**There is no SessionStart hook.** A previous draft of this change installed one and was rolled back after operator dogfooding revealed three problems (every-session overhead, operator-invisible output, cross-shell escape bugs). The current shape — agent-invoked via CLAUDE.md directive — matches `betta-tech/ejemplo-harness-subagentes` (`init.sh` from `AGENTS.md`). The rollback rationale is preserved verbatim in `design.md` Decision 5 v2 for future readers.

**Deferred operator actions** (carried into next session via `TOMORROW.md` at the repo root):

- Task 2.11 — re-run preflight in `pwsh` 7 after `winget install --id Microsoft.PowerShell`
- Task 3a.5 — natural smoke of the agent-invoked directive on the next real `/opsx:apply` session
- Task 6.7 — Notion notification + write-up of v1 lessons (and v2 backlog: CHECKPOINTS.md, leader-mode)
