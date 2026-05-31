# harness-progress-tracking Specification

## Purpose
TBD - created by archiving change harness-progress-tracking. Update Purpose after archive.
## Requirements
### Requirement: Per-change live progress file

Every change directory under `openspec/changes/<id>/` SHALL contain a `progress.md` file from the moment the proposal is created (seeded by `/opsx:propose`) until the change is archived. The file MUST be a YAML document conforming to the schema defined in `openspec/templates/progress-template.md` with the following required top-level keys: `current_task` (string, format `<section>.<number>` matching a checkbox in `tasks.md`), `last_completed` (string, same format, or `none` if no task has been closed yet), `next_step` (string, free-form one-line description of the next concrete action), `decisions_pending_design_update` (list of strings, may be empty), `blockers` (list of strings, may be empty), and `last_updated` (ISO-8601 UTC timestamp). Optional keys MAY be added without violating the requirement, but the required keys MUST all be present.

#### Scenario: New change created via /opsx:propose

- **WHEN** `/opsx:propose <name>` finishes generating the four core artefacts
- **THEN** `openspec/changes/<name>/progress.md` exists and matches the template schema with `current_task: none`, `last_completed: none`, `next_step: "ready for /opsx:apply"`, empty lists for `decisions_pending_design_update` and `blockers`, and a `last_updated` of the propose-time timestamp

#### Scenario: Active change missing progress.md

- **WHEN** `/opsx:apply <name>` is invoked and `openspec/changes/<name>/progress.md` does not exist
- **THEN** the agent running `/opsx:apply` SHOULD create it from the template before doing any other work and SHOULD log a `[recovered missing progress.md]` line to the operator. **Note (adversarial-review Finding #4, 2026-05-27):** there is no `/opsx:apply` command/skill that enforces this at runtime; the behaviour is documented in `docs/workflow.md` Gate D step 0 and depends on agent discipline. A follow-up `harness-checkpoints-v2` change is planned to add a `validate-progress.ps1` helper plus an explicit `/opsx:apply` skill patch that enforces the recovery deterministically.

### Requirement: progress.md is updated by the backend-developer subagent after every closed task

The `backend-developer` subagent SHALL rewrite `progress.md` at the end of every task it closes in `tasks.md` (i.e. after the `- [ ]` becomes `- [x]`). The update MUST set `last_completed` to the just-closed task ID, `current_task` to the next pending task ID in `tasks.md` (or `none` if all tasks are closed), `next_step` to a one-line description of that next task's first concrete action, refresh `last_updated`, and append to `decisions_pending_design_update` or `blockers` if any such state was produced during the closed task. The subagent SHALL NOT update `progress.md` after every tool call; the per-task cadence is intentional to limit write overhead.

#### Scenario: Task closed successfully with no new decisions

- **WHEN** the `backend-developer` subagent finishes task `3.2` and ticks the checkbox in `tasks.md`
- **THEN** `progress.md` is rewritten with `last_completed: 3.2`, `current_task: 3.3` (or whichever is next pending), `next_step` describing 3.3's first action, `last_updated` set to now, and `decisions_pending_design_update` + `blockers` unchanged

#### Scenario: Task closed with a mid-flight decision not yet in design.md

- **WHEN** the `backend-developer` subagent finishes a task during which it chose `aws-cli` instead of the `rclone` mentioned in `design.md` Decision 5
- **THEN** the updated `progress.md` includes a new entry in `decisions_pending_design_update` describing the swap and a one-line rationale, so the next `/opsx:archive` or `/openspec-sync-specs` invocation reconciles `design.md`

### Requirement: /opsx:apply reads progress.md at session start

The `/opsx:apply` workflow phase D ("implementation") SHALL begin every invocation by reading `openspec/changes/<id>/progress.md` and posting a one-paragraph "resuming from" summary to the operator before invoking the `backend-developer` subagent. The summary MUST cite `current_task`, `next_step`, and any non-empty entries in `decisions_pending_design_update` or `blockers`. If `last_updated` is older than the most recent commit on the change branch, the summary MUST explicitly flag the staleness so the operator can verify reality against the file.

#### Scenario: Fresh session after compaction

- **WHEN** a new Claude Code session opens and the operator runs `/opsx:apply harness-progress-tracking`
- **THEN** the main thread reads `progress.md` and posts: "Resuming from task <current_task>. Next step: <next_step>. <N> decisions pending design update, <M> active blockers." before spawning the `backend-developer` subagent

#### Scenario: progress.md is stale relative to git history

- **WHEN** the agent running `/opsx:apply` reads a `progress.md` whose `last_updated` predates the most recent commit on the change branch
- **THEN** the resuming-from summary SHOULD explicitly state `WARNING: progress.md last updated <T1> but most recent commit is <T2> by <author>; verify next_step against the latest commit before proceeding`. **Note (adversarial-review Finding #5, 2026-05-27):** there is no automated comparison code that produces this string; the check depends on agent discipline (mirrored in `docs/workflow.md` Gate D step 0). A follow-up `harness-checkpoints-v2` change is planned to add a `validate-progress.ps1` helper that automates the comparison. Until then the `adversarial-reviewer` agent's `progress.md` freshness check (added in this change) catches drift at review time as a defense in depth.

### Requirement: progress.md is archived with the change

When `/opsx:archive <id>` runs, `progress.md` SHALL be moved from `openspec/changes/<id>/` to `openspec/changes/archive/<date>-<id>/` (the project's canonical archive path per `docs/workflow.md` Phase 8) alongside `proposal.md`, `design.md`, `tasks.md`, and the change's `specs/` tree. The archived file is read-only thereafter and serves as the historical record of "what was actually done" for post-mortem and audit purposes.

#### Scenario: Change is archived after completion

- **WHEN** `/opsx:archive <id>` runs against a change whose `tasks.md` has all checkboxes ticked
- **THEN** `openspec/changes/archive/<date>-<id>/progress.md` exists with the final state, `openspec/changes/<id>/` no longer exists, and the archived `progress.md` is identical (byte-for-byte) to the version present at archive time

### Requirement: progress-template.md seeds new changes

A canonical template file SHALL live at `openspec/templates/progress-template.md` containing the YAML skeleton with all required keys, placeholder values, and inline comments explaining each field. The `/opsx:propose` workflow SHALL copy this template into every new change directory as `progress.md` with the placeholder values pre-filled for a fresh change.

#### Scenario: Template fields rendered into a new change

- **WHEN** `/opsx:propose <name>` creates `openspec/changes/<name>/progress.md` from the template
- **THEN** the resulting file contains all required keys with `current_task: none`, `last_completed: none`, `next_step: "ready for /opsx:apply"`, empty lists, and the actual propose-time `last_updated`

### Requirement: Preflight script reports session-start repo state

A PowerShell script at `scripts/preflight.ps1` SHALL produce a deterministic, structured report describing the current state of the repository when invoked. The report MUST include, in order, lines tagged `[OK]`, `[WARN]`, `[FAIL]`, or `[SKIP]` covering: number of active changes under `openspec/changes/` (excluding `archive/`); exit status of `backend/mvnw -q compile`; working tree cleanliness (`git status --porcelain` empty?); current branch name plus the last commit's short hash and subject; for each active change, presence of `proposal.md`, `design.md`, `tasks.md`, and `progress.md`; and the timestamp of the last verified Supabase backup if available. The script MUST exit 0 when no `[FAIL]` lines were emitted, non-zero otherwise. `[WARN]` lines SHALL NOT cause non-zero exit.

#### Scenario: Healthy repo with one active change

- **WHEN** an operator runs `pwsh -File scripts/preflight.ps1` on a repo whose working tree is clean, whose `backend/mvnw -q compile` succeeds, and which has exactly one active change with all four required artefact files present
- **THEN** the script prints one `[OK]` line per check, no `[WARN]`/`[FAIL]`/`[SKIP]` lines for the listed checks, and exits 0

#### Scenario: Multiple active changes

- **WHEN** an operator runs `scripts/preflight.ps1` on a repo with two active changes
- **THEN** the active-changes line is tagged `[WARN]` with the message "2 active changes — confirm intentional parallel work", every other check proceeds normally, and the script still exits 0 (warn is not failure)

#### Scenario: Build is broken

- **WHEN** an operator runs `scripts/preflight.ps1` on a repo where `backend/mvnw -q compile` exits non-zero
- **THEN** the compile-status line is tagged `[FAIL]` with the captured stderr tail, the script continues running the remaining checks, and the script exits non-zero

### Requirement: Preflight degrades gracefully when optional tools are missing

The preflight script SHALL NOT abort when an optional dependency (currently `rclone`) is unavailable on the operator's machine. Instead, the check whose tool is missing MUST emit a `[SKIP]` line stating which tool is absent and what would have been checked, and the script MUST continue with the remaining checks.

#### Scenario: rclone is not installed

- **WHEN** `scripts/preflight.ps1` runs on a machine where `rclone` is not on PATH
- **THEN** the Supabase-backup-freshness line is `[SKIP] rclone not installed — backup freshness check skipped (install rclone to enable)`, every other check proceeds normally, and the script exits 0 if no other check failed

### Requirement: CLAUDE.md instructs agents to invoke preflight before non-trivial work

`CLAUDE.md` SHALL contain a directive instructing Claude Code agents to invoke `scripts/preflight.ps1` and report its output BEFORE starting any non-trivial work (any `/opsx:apply` invocation, any code edit, any architectural decision, any commit). Sessions that are purely conversational (answering questions, exploring the codebase, explaining concepts) MAY skip preflight; this is an agent judgment call documented in CLAUDE.md, not a runtime gate. The directive MUST also state that a non-zero preflight exit code SHALL be acknowledged in the agent's first response and SHALL inform the agent's subsequent recommendations (e.g., refuse to start `/opsx:apply` when `[FAIL] mvn compile failed` is present until the operator resolves it).

**Rationale (cross-reference design Decision 5 v2):** This change does NOT install a `SessionStart` hook in `.claude/settings.json`. A previous design draft did install such a hook; it was reverted after operator dogfooding revealed the hook fires on every session regardless of intent (trivial questions paid a 3–8 s `mvn compile` tax) AND surfaces Claude-internal context that the operator cannot see in the terminal, weakening the operator's ability to verify the hook ran. The agent-invoked pattern matches the reference implementation in `betta-tech/ejemplo-harness-subagentes` (`init.sh` invoked from `AGENTS.md` instructions, not a hook).

#### Scenario: Agent starts a /opsx:apply session

- **WHEN** Claude is asked to run `/opsx:apply <change>` and has not yet invoked preflight in this session
- **THEN** Claude SHALL run `scripts/preflight.ps1` as its first tool call, report the output to the operator, and acknowledge any `[FAIL]` or `[WARN]` lines BEFORE delegating to the `backend-developer` subagent

#### Scenario: Agent starts a trivial conversational session

- **WHEN** the operator opens a Claude Code session and asks a conversational question (e.g., "explain how the savings_goals plan handles edge cases") with no code-change intent
- **THEN** Claude MAY answer directly without invoking preflight; the directive's "non-trivial work" wording explicitly permits this skip

#### Scenario: Preflight reports a build failure at the start of /opsx:apply

- **WHEN** Claude runs preflight at the start of `/opsx:apply` and the output contains `[FAIL] mvn compile failed`
- **THEN** Claude SHALL stop, report the failure to the operator, and recommend resolving the broken build before proceeding rather than starting the apply phase

