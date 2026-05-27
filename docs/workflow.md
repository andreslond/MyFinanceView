# Development Workflow — MyFinanceView

> Canonical, human-in-the-loop flow for any change in this repo. Authority: extends [base-standards.md §3](base-standards.md#3-development-methodology). When the §3 summary and this document disagree, this document wins. See also the side-window visual: [`workflow-cheatsheet.html`](workflow-cheatsheet.html).

---

## TL;DR — what this document changes vs. the old flow

The old flow listed 8 commands in a row, no checkpoints. Result: `supabase-backup-policy` shipped a 490-line `design.md` + 279-line `tasks.md` (808 lines of process artifacts for a personal-finance backup) because architecture decisions, scope, and adversarial-review findings landed without operator review.

This version adds **4 mandatory gates** (A, B, C, D) where work stops until the operator gives explicit OK, and a **finding-triage policy** that filters adversarial-review noise against the change's declared threat model.

---

## 0. Session start — what every session sees first

Every Claude Code session opened in this repo runs `scripts/preflight.ps1` via the `SessionStart` hook configured in `.claude/settings.json`. The script's stdout is injected as additional context in the first conversation turn, so the operator (and Claude) start oriented to repo state without an extra tool call.

Preflight produces one line per check tagged `[OK]` / `[WARN]` / `[FAIL]` / `[SKIP]`, then a single summary line. It covers:

- number of active changes under `openspec/changes/` (excluding `archive/`)
- `./mvnw -q compile` exit status (fast compile, NOT verify — see [base-standards.md §5](base-standards.md#5-quality-bar))
- working tree cleanliness (`git status --porcelain`)
- current branch + last commit (informational)
- per-active-change presence of `proposal.md`, `design.md`, `tasks.md`, `progress.md`
- last verified Supabase backup timestamp (via `rclone`, gracefully `[SKIP]`ped when `rclone` is not installed locally)

Exit code = number of `[FAIL]` lines; `[WARN]` and `[SKIP]` are not failures. The hook is informational only — a non-zero exit does NOT block the session (Claude acknowledges blockers and adjusts per turn).

If the first turn does NOT include preflight output, check that `scripts/preflight.ps1` exists at the worktree root and that `.claude/settings.json` carries the `hooks.SessionStart` entry. See `openspec/changes/harness-progress-tracking/design.md` Decisions 4–7 for the design rationale.

---

## 1. The 8 phases (and the 4 gates between them)

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  Phase 1: /enrich-us         (refine the user story)                         │
│  Phase 2: /opsx:explore      (OPTIONAL — investigate before propose)         │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Gate A: ARCHITECTURE DECISIONS — close every open decision with the         │
│          operator BEFORE design.md is written                                │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Phase 3: /opsx:propose      (write proposal.md + design.md + specs/ )       │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Gate B: TASK PLAN — bullet-list of tasks to be detailed, with operator OK   │
│          BEFORE tasks.md is written                                          │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Phase 4: /opsx:propose (continued — write tasks.md)                         │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Gate D: START IMPLEMENTATION — explicit "go" from operator; subagent        │
│          choice + first batch of tasks confirmed                             │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Phase 5: /opsx:apply        (implement tasks via backend-developer)         │
│  Phase 6: adversarial-review (red-team pass)                                 │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Gate C: FINDING TRIAGE — Blocker/Major auto-in, Minor/Question need OK,     │
│          all findings checked against threat model in proposal.md            │
│  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │
│  Phase 7: /commit            (focused commit + PR)                           │
│  Phase 8: /opsx:archive → /openspec-sync-specs → /update-docs                │
└──────────────────────────────────────────────────────────────────────────────┘
```

Order of gates is **A → B → D → C** by phase number — D fires before C because implementation happens before review. The letters are stable for cross-reference; don't renumber.

---

## 2. The 4 gates — what fires, what passes

### Gate A — Architecture decisions (before `design.md`)

**Fires:** after Phase 1 (story enriched) and before any `design.md` content is written.

**What Claude does:**
1. Lists every architecture-shaping decision the change will need: storage, scheduler, encryption strategy, alerting channels, host, security boundary, persistence model, etc.
2. For each open decision, presents 2–4 options with trade-offs via `AskUserQuestion` — **one decision (or one tight group) at a time**, never bundled into a 12-option mega-prompt.
3. Records each closed decision verbatim in `design.md` under "### Decisions (operator-approved)" with the alternatives that were rejected and why.

**Operator's job:** answer. If a decision should be deferred (e.g. "decide at implementation time"), say so explicitly so it lands as `[DEFERRED]` in `design.md`.

**Pass condition:** zero open decisions remain. Anything Claude wants to assume must either be explicitly approved or marked DEFERRED.

### Gate B — Task plan (before `tasks.md`)

**Fires:** after `design.md` is written and approved, before `tasks.md` is generated.

**What Claude does:**
1. Posts a bulleted plan: **N top-level tasks**, each one sentence, in implementation order.
2. Calls out which tasks are independent (could run in parallel via multiple subagent spawns) vs. sequential.
3. Calls out which tasks are operator-manual (e.g. "generate age key on workstation") vs. agent-implementable.
4. Estimates relative effort (S/M/L) — not hours; rough sizing only.

**Operator's job:** trim, reorder, split, or merge tasks. If the plan exceeds your appetite ("I want a v1, not the gold-plated version"), cut here — once `tasks.md` is detailed and adversarial-reviewed, removing scope is twice as expensive.

**Pass condition:** operator says "go" or "go with these edits".

### Gate D — Start implementation (before `/opsx:apply`)

**Fires:** between `tasks.md` complete and the first `backend-developer` spawn.

**What Claude does:**

0. **Read `openspec/changes/<id>/progress.md` first and post a one-paragraph "resuming from" summary** citing `current_task`, `next_step`, and any non-empty `decisions_pending_design_update` or `blockers`. If `progress.md.last_updated` is older than the most recent commit on the change branch, the summary MUST explicitly flag the staleness — e.g. `WARNING: progress.md last updated <T1> but most recent commit is <T2> by <author>; verify next_step against the latest commit before proceeding`. If `progress.md` is missing entirely, create it from `openspec/templates/progress-template.md` and log `[recovered missing progress.md]` before continuing. See `openspec/changes/harness-progress-tracking/design.md` Decision 2 (per-task cadence) and the `harness-progress-tracking` capability spec for the binding scenarios.
1. Confirms: "I'll spawn `backend-developer` on Sonnet 4.6 for tasks X.Y → X.Z. Expected cost: ~M tokens. Async or sequential?"
2. Names every subagent it intends to spawn and the task ranges.
3. Surfaces any pre-implementation manual setup tasks the operator owes (e.g. "task 1.2 — you need to run `age-keygen` before I can proceed").

**Operator's job:** confirm, redirect (different subagent, different model), or split the apply into smaller batches.

**Pass condition:** operator says "go".

**Note on `progress.md` updates during Phase 5:** the `backend-developer` subagent rewrites `progress.md` at the end of every closed task (see `.claude/agents/backend-developer.md` and design Decision 2). The cadence is per-task, NOT per-tool-call.

### Gate C — Finding triage (after `adversarial-review`)

**Fires:** after the adversarial-reviewer report lands and before any finding is converted into a code or scope change.

**Triage rules:**

| Severity | Default action | Operator OK required? |
|---|---|---|
| **Blocker** | Must be addressed in this change | No (informational notification) |
| **Major** | Must be addressed *unless* the change's threat model excludes it | Yes, only if rejecting |
| **Minor** | Defer to a follow-up change by default | Yes, only if accepting now |
| **Question** | Answer in a comment, no code change | Yes, only if accepting as code change |

**Threat model gate:** every `proposal.md` MUST declare a `## Threat model` section naming the adversary and what's *out of scope*. Findings out of scope are auto-rejected (recorded in `design.md` "### Rejected findings" with the reason). Example for `supabase-backup-policy`: "Adversary: opportunistic cloud-storage breach. Out of scope: local forensic adversary with physical disk access — therefore `cipher /W`, VSS clear, Tails live-USB findings auto-rejected."

**Pass condition:** every finding has a disposition (`incorporated` / `deferred to <follow-up>` / `rejected (out of threat model)`), and the operator has reviewed the Minor/Question accept list.

---

## 3. Finding-triage policy — the long version

Adversarial review is valuable. Adversarial review unfiltered is scope-creep machinery. Three rules keep it useful:

1. **Threat model first.** `proposal.md` declares the adversary, the assets, and what is *out of scope*. The reviewer is told this up front (the prompt to `adversarial-reviewer` MUST include the threat-model section verbatim).
2. **Severity gates auto-action.** Blocker/Major default to incorporated; Minor/Question default to deferred. The operator only intervenes on the exceptions.
3. **No anonymous scope creep.** Every finding that lands in code gets a back-reference in the commit message (e.g. "addresses finding #B3 of adversarial review report at <path>"). Every finding rejected gets a one-line reason in `design.md`. After the change archives, the reviewer's file is preserved under `openspec/archive/<change>/adversarial-review.md`.

If the reviewer disagrees with the threat model, that disagreement is itself a Major finding ("threat model is too narrow") and the operator decides whether to widen it or reject.

---

## 4. Async & parallel opportunities

Claude Code supports parallel subagents. Use it where the work is genuinely independent.

| Phase | Async opportunity | How |
|---|---|---|
| 2 | `/opsx:explore` research can fan out — multiple Explore subagents on different sub-questions | Spawn in parallel from main context, single message with N `Agent` calls |
| 3 | None — Gate A is human-sequential | — |
| 5 | If `tasks.md` has independent task groups (e.g. "endpoint A" vs "endpoint B"), spawn multiple `backend-developer` subagents in parallel | Use git worktrees (`isolation: "worktree"` on the Agent call) so they don't collide; reconcile by merging worktree branches |
| 6 | `adversarial-reviewer` runs as an isolated subagent; while it runs, the main thread can draft commit message, update Notion, or start writing `/update-docs` deltas | Use `run_in_background: true` on the Agent spawn |
| 8 | `/openspec-sync-specs` and `/update-docs` can run in parallel — they touch different file sets | Two parallel Agent calls or two sequential foreground runs, depending on conflict risk |

**Cost reality check:** parallel ≠ cheaper. Three subagents run for 5 minutes = 3× tokens of one for 5 minutes. Parallelism buys wall-clock time when blocked, not budget.

**When NOT to parallelize:** if the operator just wants to watch the work happen, sequential is better — easier to interject. Default: sequential. Opt-in to parallel at Gate D.

---

## 5. Command cheat-sheet

Single-line semantics. Full skill files live under `~/.claude/skills/`.

| Command | Phase | What it does | Output | Triggers gate |
|---|---|---|---|---|
| `/enrich-us` | 1 | Take a fuzzy story (Notion link or text) and refine it into a tight, testable user story | Refined story (no files yet) | — |
| `/opsx:explore` | 2 (opt) | Open-ended research mode: ask questions, read code, no artifacts yet | Conversation only | — |
| `/opsx:propose` | 3–4 | Generate proposal.md, design.md, specs/, tasks.md for a new change | `openspec/changes/<id>/*` | **A** before design.md, **B** before tasks.md |
| `/opsx:apply` | 5 | Implement tasks from a change; delegates to `backend-developer` subagent | Code + tests | **D** before first subagent spawn |
| `adversarial-review` (skill) or `Agent(adversarial-reviewer)` | 6 | Red-team review of the change before merge | Findings report (markdown) | **C** after report |
| `/commit` | 7 | Focused commit + PR following repo conventions | Git commit + PR URL | — |
| `/opsx:archive` | 8 | Move the change folder to `openspec/changes/archive/<date>-<id>/`, mark complete | Updated archive | — |
| `/openspec-sync-specs` | 8 | Merge delta specs from the change into canonical `openspec/specs/<capability>/spec.md` | Updated canonical specs | — |
| `/update-docs` | 8 | Sync SPEC.md, docs/, Notion to reflect what landed | Doc edits | — |

**Note:** `/opsx:propose` is split across phases 3 and 4 deliberately because Gate B sits in the middle. Internally it's one skill, but the operator interacts with it twice.

---

## 6. Worked example — `supabase-backup-policy` redux

How the current in-flight change *would have* gone with the gates active. (The change as it sits today predates this manual; do not retro-apply.)

### Gate A — what should have been asked

```
1. Storage destination?
   - Cloudflare R2  ← recommended (existing bucket, S3-compat, cheap)
   - AWS S3
   - Backblaze B2
2. Scheduler?
   - n8n on existing VPS  ← recommended (already running)
   - Supabase pg_cron
   - GitHub Actions
3. Encryption strategy?
   - age single-recipient  ← v1 recommended for personal app
   - age dual-recipient (primary + recovery)
   - GPG with hardware key
4. Alerting?
   - Uptime Kuma push monitor (already running)  ← recommended
   - healthchecks.io
   - Both (defense in depth)
5. Threat model?
   - Adversary = opportunistic cloud breach  ← recommended for v1
   - Adversary includes local forensic actor
   - Adversary includes nation-state
```

Operator-approved threat model would have been "opportunistic cloud breach", auto-rejecting the entire forensic-recovery branch of findings (`cipher /W`, VSS clear, Tails live-USB, dual-recipient age, geographically-separated paper printouts). `design.md` ends up ~150 lines, not 490. `tasks.md` ends up ~80 lines, not 279.

### Gate C — what the triage would have done

The change as written treated ~30 findings as "all must be incorporated". Under the new policy:
- Blockers (B1, B5): incorporated ✓
- Majors inside threat model (M1, M3, M15): incorporated ✓
- Majors outside threat model (M7-forensic, M9-forensic): auto-rejected, one-line reason in design.md
- Minors and Questions: deferred to a follow-up `backup-hardening-v2` change

---

## 7. Operator quick-reference (what to actually do)

Open `workflow-cheatsheet.html` in a side window. When Claude pauses, you'll see one of:

- **"Gate A — Decision 1 of N"**: answer the `AskUserQuestion`. Trim trade-offs you don't care about.
- **"Gate B — Task plan ready for review"**: read the bullets. Say "go", "cut tasks X,Y", or "split task Z".
- **"Gate D — Ready to implement"**: confirm the subagent + batch. Or say "let me run the manual prereqs first".
- **"Gate C — N findings, M need your call"**: review the M items only. Default actions are pre-filled.

If Claude doesn't pause at a gate, **interrupt and call it out**. The gates are the contract; skipping one without operator OK is a process bug, not a feature.

---

## 8. Maintenance

- Update this document when a phase or gate changes. Bump the date below.
- Cross-link from any new skill's `SKILL.md` when it interacts with a gate.
- Last updated: 2026-05-21.
