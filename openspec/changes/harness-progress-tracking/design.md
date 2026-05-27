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

### Decision 5 — Hook command resolution: `pwsh` preferred, `powershell.exe` fallback

The SessionStart hook entry in `.claude/settings.json` uses a small wrapper command that tries `pwsh` first and falls back to `powershell.exe -File scripts\preflight.ps1`. This handles the realistic case of an operator running PS 5.1 only (the Windows 10 default) without forcing them to install PS 7 just to start a session.

Concretely:
```jsonc
{
  "hooks": {
    "SessionStart": [
      {
        "type": "command",
        "command": "pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1 2>nul || powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\\preflight.ps1"
      }
    ]
  }
}
```

`-NoProfile` keeps execution fast (skips loading the operator's profile.ps1). `-ExecutionPolicy Bypass` prevents a session-blocking prompt on machines where the default policy is `Restricted`.

**Alternatives considered:**
- *`pwsh` only* — breaks for any operator without PS 7.
- *`powershell.exe` only* — works everywhere on Windows but doesn't get PS 7 niceties when available.
- *No fallback, fail loudly* — punishes operators for not having PS 7 installed; the script itself is PS 5.1-compatible, so there's no reason to gate the session on the runtime version.

### Decision 6 — Preflight exit code semantics: `[FAIL]` → non-zero, `[WARN]` → zero

`[FAIL]` lines (compile broken, working tree dirty unexpectedly, mandatory file missing) cause exit ≠ 0; `[WARN]` lines (>1 active change, optional tool missing → routed to `[SKIP]`) do not. Conflating the two means a legitimate "I'm intentionally working on two changes" warns the operator into a panic; separating them lets the hook output stay informational while still surfacing real blockers.

**Alternatives considered:**
- *Any non-OK is non-zero* — too noisy; the WARN case (>1 active change) is legitimate in this project (we have supabase-backup-policy + harness-progress-tracking active right now).
- *No exit code at all (always 0)* — defeats the purpose of having a "go ahead" signal.

### Decision 7 — Hook is informational, not blocking

The SessionStart hook surfaces output as additional context but does NOT block the session if preflight exits non-zero. Claude reads the `[FAIL]` line and decides per turn whether to acknowledge it or proceed. This matches the project's "documentation-gate, not runtime-enforced" stance baked into the Supabase write rule.

**Alternatives considered:**
- *Hard block (exit non-zero from hook aborts session)* — would prevent legitimate "I need to fix the broken build" sessions from starting at all; the cure is worse than the disease.
- *Block only on a specific FAIL subset* — adds policy complexity without clear value; trust the operator + Claude to read the report.

### Decision 8 — `progress.md` is git-tracked (not gitignored)

The file is committed alongside other change artefacts. Concurrent-session race conditions surface as git merge conflicts, which is fine for a single-operator project. Tracking it also means archived changes carry their final `progress.md` into `openspec/archive/<id>/`, providing the historical "what was actually done" trail.

**Alternatives considered:**
- *Gitignore it (session-local)* — defeats the purpose; a fresh clone wouldn't see in-flight progress, and the historical record is lost.
- *Track but in a separate `.openspec-state/` directory* — splits the change artefacts across two locations for no benefit.

## Risks / Trade-offs

[Risk] Hook output adds tokens to every session. → Mitigation: terse single-line-per-check format (deliberate ASCII table, no JSON), tag-prefixed (`[OK]`/`[WARN]`/`[FAIL]`/`[SKIP]`) so Claude can scan in O(lines) without parsing.

[Risk] `progress.md` goes stale because the subagent forgets to update it. → Mitigation: `adversarial-review` is briefed (via the agent definition update) to flag `progress.md.last_updated < HEAD commit timestamp` as a Minor finding. The `/opsx:apply` "resuming from" summary also surfaces the staleness in plain English.

[Risk] Two sessions touch the same change concurrently and produce conflicting `progress.md` writes. → Mitigation: git's merge surface. Acceptable for single-operator; if multi-operator ever lands, a `change-lock` follow-up addresses it.

[Risk] Preflight script breaks on a Windows version we don't test (older Server SKUs, ARM, etc.). → Mitigation: script is PS 5.1-compatible (the lowest-common-denominator on supported Windows); no PS 7-only cmdlets. Falls back gracefully if `git`, `mvn`, or `rclone` is absent. The hook itself swallows preflight failure (`|| true`-equivalent) so a broken preflight never prevents a session.

[Risk] `pwsh`/`powershell.exe` invocation through the hook fails because of an `ExecutionPolicy` setting in a managed environment. → Mitigation: explicit `-ExecutionPolicy Bypass` per Decision 5; the script is unsigned and intentionally so (signing infrastructure is out of scope for v1).

[Risk] The subagent's per-task `progress.md` rewrite drifts from the schema (e.g., adds a top-level key the schema doesn't list, removes a required one). → Mitigation: the template carries inline comments documenting required vs optional keys. A follow-up `harness-checkpoints` change can add a `validate-progress.ps1` that enforces the schema as part of preflight; v1 ships without enforcement and relies on subagent discipline + adversarial review.

[Trade-off] We accept that `progress.md` is not real-time accurate to within seconds — it lags by one task. The trade vs constant rewrites is tokens + write churn for staleness bounded to "the most recent task". Acceptable.

[Trade-off] We accept that the SessionStart hook adds a 1–3 s delay at session start (PowerShell startup + `mvn compile` cold-start cost). For sessions that do real work this is invisible; for sessions that are just a question ("what's the date?"), it's a small tax. Worth it.

## Migration Plan

This is a greenfield process-tooling change — no data migration, no rollback drama. Steps:

1. Land `openspec/templates/progress-template.md` with the YAML skeleton.
2. Land `scripts/preflight.ps1` and smoke-test it manually from PowerShell 5.1 AND `pwsh` 7 on the operator's Windows machine.
3. Add the SessionStart hook entry to `.claude/settings.json`; verify a new Claude Code session surfaces the preflight output.
4. Update `docs/workflow.md` Phase D (read + write `progress.md`) and the "session start" preamble (preflight output is now expected).
5. Update `.claude/agents/backend-developer.md` with the `progress.md` update directive.
6. Backfill `progress.md` into existing active changes (`supabase-backup-policy`, this change `harness-progress-tracking`) by hand using the template, populating `current_task` / `last_completed` from the current `tasks.md` checkbox state.
7. Update `CLAUDE.md` with a pointer to preflight + progress.md in the workflow section.
8. Optionally update the `openspec-propose` skill (or document a manual step) to copy the template at change-creation time. If the skill is upstream-maintained and we can't patch it, the operator runs a small `Copy-Item` after `openspec new change` until the upstream supports templates natively — documented in `docs/workflow.md`.

**Rollback:** delete `progress-template.md` + `preflight.ps1`, remove the SessionStart hook entry, revert the two doc files, leave the existing `progress.md` files in active changes as harmless extra artefacts (or `git rm` them if desired). Zero side-effects beyond the deleted files.

## Open Questions

1. **Does the `openspec-propose` skill support a template copy step out-of-the-box, or do we need a manual `Copy-Item` after `openspec new change`?** The skill instructions don't mention templates; we'll discover during the `/opsx:apply` implementation phase. If not, we document a manual step in `docs/workflow.md` and file a follow-up.
2. **Should the `[WARN] 2 active changes` line block when the count exceeds some threshold (e.g., 4)?** v1 says no — it's always warn-only. Revisit if the project routinely runs many parallel changes.
3. **Should preflight cache its slowest check (`mvn compile`) between sessions if the working tree is unchanged?** Plausible 1-second saving per session start; deferred to a follow-up if the start cost becomes annoying in practice.
4. **Should the `adversarial-reviewer` agent's prompt be updated as part of this change to look for stale `progress.md`?** Probably yes — `[[adversarial-reviewer]]` is a project agent we control. v1 plan: include a small one-line addition to its prompt in the implementation tasks. If it adds friction we drop it.
5. **Should the SessionStart hook also dump `progress.md` for every active change as part of its output?** Tempting but probably too noisy when many changes exist. v1 says no — preflight prints `presence/absence` per change, `/opsx:apply` reads the specific change's `progress.md` on demand.
