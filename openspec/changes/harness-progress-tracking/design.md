## Context

Two friction points show up in every multi-session OpenSpec change on this project:

1. **Compaction kills context.** The `supabase-backup-policy` change ran for ~5 sessions; each compaction dropped the operator + Claude into a state where "where was I in tasks.md?" came from a chat summary, not from a file the next session could reliably read. Recovery cost minutes per resumption and risked re-doing or skipping steps.
2. **Sessions start blind.** Nothing runs before the operator's first message. The operator (and Claude) discover repo state — active changes, build health, working-tree cleanliness, last verified Supabase backup — through ad-hoc tool calls scattered across the first 5–10 turns. By then the conversation has already drifted into the wrong assumption (e.g. "let me just run flyway" while the backup is 48 h old).

The pattern is borrowed from `betta-tech/ejemplo-harness-subagentes` (`init.sh` + `progress/current.md` + `CHECKPOINTS.md`), but the source project has a central `feature_list.json` whose "one feature in_progress" invariant is enforced. OpenSpec already has `openspec/changes/<id>/` directories with `proposal.md` + `design.md` + `tasks.md` per change — there is no central registry to add, only a per-change progress file alongside the existing artefacts. The preflight script + SessionStart hook are net-new but live entirely under `scripts/` and `.claude/`.

Constraints inherited from the project:
- **Windows-first OS.** Operator's primary environment is Windows 10. Bash works (via the Bash tool / Git Bash), but PowerShell is the native shell and the only one guaranteed available without WSL/Git-Bash. The hook command must work in stock PowerShell 5.1 as a fallback for any operator running before installing `pwsh` 7+.
- **No automated CI today.** `SPEC.md` confirms there is no GitHub Actions / Jenkins / etc. wired up; everything runs locally. The preflight script is the only "things should be green" gate before any session.
- **Modular monolith, no microservices.** The script + hook + progress file are tooling around the existing monolith; they are not a new service.
- **`backend-developer` subagent runs on Sonnet 4.6** ([[feedback-backend-developer-model]]). The progress.md write directive must be cheap enough that running it after every closed task does not balloon Sonnet's output tokens — hence "rewrite the file" rather than "compute a diff" or "track history".
- **HITL gates** ([[feedback-workflow-hitl-gates]]). Gate A was pre-approved by the operator on 2026-05-27; this design is the artefact that feeds Gate B.

Stakeholder: the operator (Andres Torres, single developer). No external users, no production downtime concern — a broken preflight script means "operator runs it manually, fixes it" — not "outage". This permits a v1 with rough edges that get sanded in follow-up changes.

## Goals / Non-Goals

**Goals:**
- Every active OpenSpec change carries a machine-readable `progress.md` that any new session can read in under one Read tool call.
- Every Claude Code session in this repo starts with a one-screen, deterministic report of repo state injected as additional context — no extra tool calls needed for the basics.
- The cost of writing `progress.md` after every task is small enough that the `backend-developer` subagent on Sonnet 4.6 absorbs it without measurable slowdown.
- The whole change is reversible by deleting three files (`progress-template.md`, `preflight.ps1`, the SessionStart hook entry) and reverting two docs (`workflow.md`, `backend-developer.md`) — no migrations, no data, no state.

**Non-Goals:**
- **No CHECKPOINTS.md repo-wide invariants.** Deferred to a follow-up change once v1 proves out the SessionStart hook plumbing.
- **No strict leader-mode in CLAUDE.md.** The existing `[[feedback-opsx-apply-via-backend-developer]]` memory already covers the main risk; formalizing leader-only via CLAUDE.md is high-friction for the operator's single-dev scale.
- **No append-only `history.md`.** Git log + auto-memory already cover 80% of the value; marginal addition not worth the file.
- **No CI integration.** The repo has no CI today; adding one is its own épica.
- **No cross-session locking on `progress.md`.** Single-operator project; two concurrent sessions on one change is itself a process smell.
- **No "what's done" source-of-truth in `progress.md`.** `tasks.md` checkboxes remain authoritative for that; `progress.md` is "what to do NEXT + mid-flight decisions not yet in design.md".
- **No telemetry or analytics about preflight output.** It's a local stdout report, full stop.

## Decisions

### Decision 1 — `progress.md` is YAML, not free-form markdown

The file is a YAML document (no front-matter, no surrounding prose) so it can be parsed by a one-line `ConvertFrom-Yaml` (PowerShell 7) or `yaml.safe_load` (Python in future tooling) without regex gymnastics. Free-form markdown was considered for "narrative friendliness" but rejected: drift is the v1 risk, and a parseable file lets a future check ("is `last_updated` within N hours of HEAD?") be one-line. The schema is intentionally tiny (6 keys) so it stays human-readable.

**Alternatives considered:**
- *Free-form markdown with conventional headings* — drift risk, no automated validation.
- *JSON* — denser, but ugly for humans to edit; the file IS hand-edited when reconciling mid-flight decisions.
- *TOML* — fine, but PowerShell's TOML support is third-party while YAML works with the bundled `powershell-yaml` module + has wide Python/Node coverage.

### Decision 2 — Update cadence is "after every closed task", not "after every tool call"

The `backend-developer` subagent rewrites `progress.md` once per task it closes — not after every Edit / Write / Bash call. Per-tool-call updates were rejected during Gate A as overhead: a single task can involve 10–30 tool calls, and writing the file 30 times would multiply Sonnet's output tokens for no informational gain (the granular per-tool-call state is already in the agent's own transcript and is ephemeral by design). Per-task cadence matches the granularity at which the operator actually asks "where am I?".

**Alternatives considered:**
- *After every tool call* — token-expensive, low marginal information.
- *Only at session end* — defeats the purpose; if the session crashes mid-task, the file is stale.
- *On a timer (every N minutes)* — adds complexity (need a background process) for no clear win.

### Decision 3 — One `progress.md` per change directory, not one central file

The file lives at `openspec/changes/<id>/progress.md`, alongside `proposal.md` / `design.md` / `tasks.md`. A central file (`openspec/PROGRESS.md` or `feature_list.json`-style registry) was considered for the "what's the global state?" question but rejected: OpenSpec's directory structure already IS the registry — listing `openspec/changes/` tells you what's active. Co-locating per-change progress with per-change spec keeps the artefacts together at archive time (single `mv` instead of stitching state from two places).

**Alternatives considered:**
- *Central `openspec/PROGRESS.md`* — duplicates the directory listing, has to be kept in sync when a change is created or archived, single contention point if two sessions touch different changes.
- *`feature_list.json`-style central registry* — same problems as above, plus introduces a JSON schema for state OpenSpec already encodes structurally.

### Decision 4 — Preflight script is PowerShell, not Bash

PowerShell is the operator's native shell and the only one guaranteed to be on PATH on a fresh Windows 10 install. The Bash tool exists but requires Git Bash / WSL, which the operator has but a future operator picking up the project might not. PowerShell 7+ (`pwsh`) is preferred for `-File` invocation and modern features; the script is written to also work in Windows PowerShell 5.1 (no PS7-only cmdlets, classic `try/catch` instead of `??=`, etc.) so the hook command can fall back. A separate Bash version was considered but rejected for v1 — one script, one truth, until a non-Windows operator joins.

**Alternatives considered:**
- *Bash + Git Bash assumption* — works today, but the SessionStart hook would silently fail on any operator who hasn't installed Git Bash; less defensive.
- *Cross-platform Node CLI* — adds a Node + npm dependency for what is a 50-line shell report.
- *Python (operator already has it for Supabase work)* — comparable to PowerShell but adds a runtime the hook depends on; PowerShell is already present.

### Decision 5 (v2) — Preflight is agent-invoked via CLAUDE.md directive, NOT a SessionStart hook

**REVISED 2026-05-27 after operator dogfooding of v1.** The original Decision 5 specified a `SessionStart` hook in `.claude/settings.json` that fires on every session. That design landed and was tested in a fresh session, where Smoke C revealed two problems:

1. **Every-session overhead.** `mvn -q compile` (cold) costs 3–8 seconds. The operator's actual usage mixes "I need to do real work" sessions with "explain this code" / "what's in this file" trivial sessions. The hook taxed all of them equally — wrong default for a single-developer project with mixed session intents.
2. **Operator can't see the output.** Per Claude Code docs, SessionStart stdout is injected as additional context for Claude but is NOT printed in the operator's terminal. The first dogfood session looked broken ("parece que no se ejecuta") even though it was working correctly — the operator only saw the result by asking Claude to quote its session-start context.

The replacement design follows the `betta-tech/ejemplo-harness-subagentes` pattern: the agent reads `CLAUDE.md` at session start, sees the directive "invoke `scripts/preflight.ps1` and report its output before non-trivial work", and decides per-session whether the work qualifies. Trivial sessions pay zero overhead; real work runs preflight as the agent's first tool call (and the operator SEES it in the conversation, not as invisible context).

`CLAUDE.md` carries the directive verbatim — there is no settings hook, no shell escape laberinto, no JSON schema fragility. The agent is the gate.

**Alternatives considered:**
- *SessionStart hook* — REJECTED (v1 implementation, rolled back). The hook fires on every session regardless of intent, takes 3–8 s, and produces output that the operator can't see in the terminal. Reverting to v2 also removes a class of hook-runtime bugs (Git Bash backslash escapes, cmd-vs-Bash redirect mismatches, `matcher`-vs-direct-entry schema confusion) — all three bugs were hit during dogfooding before the rollback decision.
- *Hook with bypass env var (Option D from the rollback discussion)* — half-measure. Still requires operators to remember `$env:SKIP_PREFLIGHT=1` for trivial sessions; cognitive load > benefit.
- *Move hook to `.claude/settings.local.json` (gitignored, operator-controlled)* — defeats the purpose of "everyone in the project starts from the same baseline" and pushes the install-the-hook decision onto a future operator who clones the repo.
- *No preflight at all* — loses the value entirely. The agent-invoked variant keeps the value (deterministic repo-state report at the start of real work) without the cost.

### Decision 6 — Preflight exit code semantics: `[FAIL]` → non-zero, `[WARN]` → zero

`[FAIL]` lines (compile broken, working tree dirty unexpectedly, mandatory file missing) cause exit ≠ 0; `[WARN]` lines (>1 active change, optional tool missing → routed to `[SKIP]`) do not. Conflating the two means a legitimate "I'm intentionally working on two changes" warns the operator into a panic; separating them lets the report stay informational while still surfacing real blockers. **Still meaningful under Decision 5 v2:** the agent reads the exit code and uses it as a "go ahead?" signal before starting non-trivial work — exit ≠ 0 triggers the "stop and ask the operator" behaviour mandated by `CLAUDE.md`.

**Alternatives considered:**
- *Any non-OK is non-zero* — too noisy; the WARN case (>1 active change) is legitimate in this project.
- *No exit code at all (always 0)* — defeats the purpose of having a "go ahead" signal that the agent can branch on.

### Decision 7 (v2) — Preflight is informational, not runtime-enforced

The preflight report is consumed by the agent (not by a hook, not by CI, not by a wrapper) and the agent decides what to do with it. There is no runtime mechanism that refuses to start a session, refuses to start `/opsx:apply`, or refuses any other command. This matches the project's "documentation-gate, not runtime-enforced" stance baked into the Supabase write rule (`[[supabase-production-data]]`) and the workflow gates in `docs/workflow.md` (operator OK is the gate, not a script).

**Alternatives considered:**
- *Hard block via hook exit code* — was the v1 plan; rejected with Decision 5 v2.
- *CI gate that runs preflight on every PR* — premature; the project has no CI today (per `SPEC.md`).
- *Pre-commit hook that runs preflight* — would tax every commit, not every session — same overhead complaint, different surface.

### Decision 8 — `progress.md` is git-tracked (not gitignored)

The file is committed alongside other change artefacts. Concurrent-session race conditions surface as git merge conflicts, which is fine for a single-operator project. Tracking it also means archived changes carry their final `progress.md` into `openspec/archive/<id>/`, providing the historical "what was actually done" trail.

**Alternatives considered:**
- *Gitignore it (session-local)* — defeats the purpose; a fresh clone wouldn't see in-flight progress, and the historical record is lost.
- *Track but in a separate `.openspec-state/` directory* — splits the change artefacts across two locations for no benefit.

## Risks / Trade-offs

[Risk] Preflight output adds tokens to every real-work session. → Mitigation: terse single-line-per-check format (deliberate ASCII table, no JSON), tag-prefixed (`[OK]`/`[WARN]`/`[FAIL]`/`[SKIP]`) so Claude can scan in O(lines) without parsing. Under Decision 5 v2 (agent-invoked), trivial sessions skip preflight entirely — they pay zero token cost.

[Risk] `progress.md` goes stale because the subagent forgets to update it. → Mitigation: `adversarial-review` is briefed (via the agent definition update) to flag `progress.md.last_updated < HEAD commit timestamp` as a Minor finding. The `/opsx:apply` "resuming from" summary also surfaces the staleness in plain English.

[Risk] Two sessions touch the same change concurrently and produce conflicting `progress.md` writes. → Mitigation: git's merge surface. Acceptable for single-operator; if multi-operator ever lands, a `change-lock` follow-up addresses it.

[Risk] Preflight script breaks on a Windows version we don't test (older Server SKUs, ARM, etc.). → Mitigation: script is PS 5.1-compatible (the lowest-common-denominator on supported Windows); no PS 7-only cmdlets. Falls back gracefully if `git`, `mvn`, or `rclone` is absent. Under Decision 5 v2 (agent-invoked), a broken preflight surfaces as a tool-call error to the agent, which reports it to the operator — no silent session failure.

[Risk] `powershell.exe` invocation by the agent fails because of an `ExecutionPolicy` setting in a managed environment. → Mitigation: the agent invokes the script via `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1`. The script is unsigned and intentionally so (signing infrastructure is out of scope for v1).

[Risk] Agent forgets to run preflight before non-trivial work. → Mitigation: CLAUDE.md carries the directive as a clear bullet in the workflow section, and the `backend-developer` subagent's definition mirrors it. `adversarial-review` is briefed to flag missing-preflight evidence at the start of a `/opsx:apply` session as a Minor finding (category `process-tooling`). The runtime cost of forgetting is low — the agent can run preflight at any later point in the session and recover.

[Risk] The subagent's per-task `progress.md` rewrite drifts from the schema (e.g., adds a top-level key the schema doesn't list, removes a required one). → Mitigation: the template carries inline comments documenting required vs optional keys. A follow-up `harness-checkpoints` change can add a `validate-progress.ps1` that enforces the schema as part of preflight; v1 ships without enforcement and relies on subagent discipline + adversarial review.

[Trade-off] We accept that `progress.md` is not real-time accurate to within seconds — it lags by one task. The trade vs constant rewrites is tokens + write churn for staleness bounded to "the most recent task". Acceptable.

[Trade-off] We accept that the agent might run preflight twice in the same session (once at start, once after a long pause). The cost is one extra `mvn -q compile` (3–8 s) and a few tokens. Lower-cost than the alternative ("the agent skips preflight because it ran 30 minutes ago and the state has since changed").

## Migration Plan

This is a greenfield process-tooling change — no data migration, no rollback drama. Steps:

1. Land `openspec/templates/progress-template.md` with the YAML skeleton.
2. Land `scripts/preflight.ps1` and smoke-test it manually from PowerShell 5.1 on the operator's Windows machine. (PS 7 / `pwsh` smoke is deferred — Decision 5 v2 means the agent invokes `powershell.exe` directly; pwsh-preferred-with-fallback complexity is no longer relevant.)
3. **NO SessionStart hook.** A previous draft of this step added a hook to `.claude/settings.json`; that draft was rolled back per Decision 5 v2.
4. Update `docs/workflow.md` Phase D / Gate D (read + write `progress.md`) and rewrite the `## 0. Session start` section to describe agent-invoked preflight (replacing the prior hook-based wording).
5. Update `.claude/agents/backend-developer.md` with the `progress.md` update directive AND the preflight-before-non-trivial-work directive (mirrors CLAUDE.md so the subagent doesn't have to re-derive it from project-level CLAUDE.md).
6. Backfill `progress.md` into existing active changes (`supabase-backup-policy`, this change `harness-progress-tracking`) by hand using the template, populating `current_task` / `last_completed` from the current `tasks.md` checkbox state.
7. **Update `CLAUDE.md` with the canonical preflight directive** (the primary implementation of Decision 5 v2): "Before non-trivial work in this repo (any `/opsx:apply`, any code edit, any architectural decision, any commit), run `scripts/preflight.ps1` first and report the output. Trivial conversational sessions may skip it. A non-zero exit MUST be acknowledged in the agent's first response."
8. Optionally update the `openspec-propose` skill (or document a manual step) to copy the template at change-creation time. If the skill is upstream-maintained and we can't patch it, the operator runs a small `Copy-Item` after `openspec new change` until the upstream supports templates natively — documented in `docs/workflow.md`.

**Rollback:** delete `progress-template.md` + `preflight.ps1`, revert the doc-only changes (CLAUDE.md, workflow.md, backend-developer.md, adversarial-reviewer.md), leave the existing `progress.md` files in active changes as harmless extra artefacts (or `git rm` them if desired). Zero side-effects beyond the deleted files. **Note:** under Decision 5 v2 there is no SessionStart hook to remove.

## Open Questions

1. **Does the `openspec-propose` skill support a template copy step out-of-the-box, or do we need a manual `Copy-Item` after `openspec new change`?** RESOLVED during implementation: the project-level `.claude/commands/opsx/propose.md` skill was patched directly AND a manual fallback was documented in `docs/workflow.md` Phase B. Belt-and-suspenders.
2. **Should the `[WARN] 2 active changes` line block when the count exceeds some threshold (e.g., 4)?** v1 says no — it's always warn-only. Revisit if the project routinely runs many parallel changes.
3. **Should preflight cache its slowest check (`mvn compile`) between sessions if the working tree is unchanged?** Less pressing under Decision 5 v2 (agent decides when to run preflight; trivial sessions skip it entirely). Deferred to a follow-up if the per-real-session cost becomes annoying in practice.
4. **Should the `adversarial-reviewer` agent's prompt be updated as part of this change to look for stale `progress.md`?** RESOLVED: yes, included in task 4.4 of the implementation. Also extended to flag "missing preflight evidence at the start of a `/opsx:apply` session" (Decision 5 v2 reinforcement) as a Minor `process-tooling` finding.
5. **Should preflight also dump `progress.md` for every active change as part of its output?** Under Decision 5 v2 (agent-invoked, not auto-fired), this becomes more reasonable — operators see the output directly. v1 still says no (keeps preflight terse; agent reads specific `progress.md` files on demand). Revisit after dogfooding several real `/opsx:apply` sessions.
6. **(New under Decision 5 v2)** When the agent runs preflight at the start of `/opsx:apply` and the report has `[FAIL] mvn compile failed`, the spec says "stop and ask the operator". But what if the broken compile is INTENTIONAL (e.g., the operator just started a refactor that left the build red and is asking Claude to help fix it)? v1 plan: the agent's "stop and ask" response includes an explicit "is this intentional?" question; operator answers and Claude proceeds. Formalize this back-and-forth in CLAUDE.md if the friction becomes noticeable.
