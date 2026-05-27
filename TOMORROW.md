# TOMORROW.md — handoff state at end of 2026-05-27 session

> Single source of truth for "what's left" when you reopen tomorrow. Delete this file once everything below is resolved.

## What landed today

- **`supabase-backup-policy` v3 cuts** committed on branch `supabase-cuts` at `52780b0` (drop dual-recipient age, defer healthchecks.io, drop public webhook, drop forensic-recovery guidance). Spec-level only; no implementation yet.
- **`harness-progress-tracking` change fully implemented** on branch `harness-progress-tracking` (9 commits ahead of main). Includes:
  - `openspec/templates/progress-template.md` (YAML schema for per-change progress files)
  - `scripts/preflight.ps1` (PowerShell repo-state report; PS 5.1-compatible)
  - CLAUDE.md directive that the agent invokes preflight before non-trivial work (agent-invoked pattern — there is NO SessionStart hook; v1 had one and was rolled back per Decision 5 v2)
  - `progress.md` seeded in both active changes (this one + supabase-backup-policy)
  - Updates to `docs/workflow.md §0`, `docs/base-standards.md §3`, `.claude/agents/backend-developer.md`, `.claude/agents/adversarial-reviewer.md`, `.claude/commands/opsx/propose.md`, `.claude/skills/openspec-propose/SKILL.md`
- **Adversarial review dispatched as background subagent** (agentId `abd11134f6912cc77`). Report at `openspec/changes/harness-progress-tracking/adversarial-review.md`. Triage applied at archive time per Gate C policy (no security adversary declared, so process-tooling findings only).
- **`harness-progress-tracking` archived + merged to main** (see today's final commit on `main`).

## What's pending — pick up here tomorrow

### 1. Deferred operator smokes (low effort)

- **Task 2.11 of harness change** — install `pwsh` 7 and re-run preflight in PS 7 to confirm parity:
  ```powershell
  winget install --id Microsoft.PowerShell
  pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1
  $LASTEXITCODE
  ```
  Tick task 2.11 in `openspec/archive/harness-progress-tracking/tasks.md` if it passes.

- **Task 3a.5 of harness change** — natural smoke of the agent-invoked directive. The next real `/opsx:apply` session you run in this repo IS the smoke — verify Claude's first action is `powershell.exe ... scripts/preflight.ps1` and the output appears in chat (not invisible context). Tick 3a.5 after confirmation.

### 2. `supabase-cuts` branch cleanup (needs investigation)

The worktree at `.claude/worktrees/supabase-cuts` has **25 modified files** that were never committed and are unrelated to the v3 cuts:

```
.claude/agents/adversarial-reviewer.md, code-auditor.md, frontend-developer.md, product-strategy-analyst.md
.claude/commands/opsx/{apply,archive,explore,propose}.md
.claude/skills/{enrich-us,openspec-apply-change,openspec-archive-change,openspec-explore,openspec-propose}/SKILL.md
.gitignore
CLAUDE.md
docs/api-spec.yml, documentation-standards.md, frontend-standards.md, workflow-cheatsheet.html, workflow.md
openspec/changes/supabase-backup-policy/{design,proposal,tasks}.md
plans/savings-goals-plan.md
skills-lock.json
```

These show as `M` from the worktree's perspective vs branch HEAD `52780b0`. Source unknown — likely a stale subagent session that wrote across many files without committing.

**Action tomorrow:**
1. `cd C:\dev\workspace\MyFinanceView\.claude\worktrees\supabase-cuts`
2. `git diff` each file and decide: keep (commit), discard (`git checkout --`), or cherry-pick to a separate branch.
3. The `openspec/changes/supabase-backup-policy/*` dirty files might overlap with the v3 cuts already committed in `52780b0` — check carefully; could be redundant edits.
4. Once clean, decide whether to merge `supabase-cuts` to `main` or keep it for the actual implementation epic (which hasn't started yet — only the v3 spec cuts landed).

### 3. Orphan worktree cleanup

```
C:/dev/workspace/MyFinanceView/.claude/worktrees/agent-a4fc9f0f4a65accdd  c1baf9d [worktree-agent-a4fc9f0f4a65accdd] locked
```

Leftover from a stale Claude Code subagent. To remove:

```bash
git worktree unlock C:/dev/workspace/MyFinanceView/.claude/worktrees/agent-a4fc9f0f4a65accdd
git worktree remove --force C:/dev/workspace/MyFinanceView/.claude/worktrees/agent-a4fc9f0f4a65accdd
git branch -D worktree-agent-a4fc9f0f4a65accdd
```

Quick check first (`git -C <path> log --oneline -3`) to confirm nothing valuable.

### 4. `origin/main` is 5+ commits behind `main` (local-only)

Today's session and the prior workflow-gates commits never pushed:

```
eb9b7f7 chore(gitignore): ignore Claude Code local state
bf7245e chore(frontend-developer): pin to Sonnet 4.6
f6dc4bb chore(adversarial-reviewer): write report to file, terse stdout
97a35af docs(lombok): allow Lombok where Records do not fit
9369ccd docs(workflow): add HITL gates workflow + cheatsheet
+ all the harness-progress-tracking commits merged today
```

**Decision needed:** push `main` to `origin/main`? `git push origin main`. No PR review (solo project), low risk.

### 5. Adversarial review findings to triage

If `openspec/changes/harness-progress-tracking/adversarial-review.md` (now under `openspec/archive/harness-progress-tracking/` after merge) has Major or Question findings, decide:

- **Major** → file as follow-up TODOs in `progress.md > decisions_pending_design_update` of the archived change OR open a new change `harness-progress-tracking-followup`.
- **Question** → apply Gate C: out-of-scope (no security adversary) → auto-reject; in-scope (process tooling gaps) → answer in the archive note.

### 6. Notion notification (task 6.7 of harness change)

Update the [project page](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57) with:
- "TASK-HARNESS-PROGRESS landed" — link to the merge commit
- Lessons-learned: hook-vs-directive trade-off, Smoke C revealed bugs that implementer-level smokes missed
- Next harness piece if v1 proves useful: **CHECKPOINTS.md** (objective end-state invariants) and/or **strict leader-mode** in CLAUDE.md — these were Option E's deferred siblings; revisit after 1-2 weeks of using the v1 harness

### 7. Quality-of-life polishes for preflight (none blocking)

- The `[FAIL] mvn compile failed` line captures Java 25 `sun.misc.Unsafe` deprecation warnings instead of the actual compile error (stderr ordering issue). Fix: `grep -E 'ERROR|error:' < stderr | tail -n 5`. See `progress.md > decisions_pending_design_update`.
- Consider adding a `--verbose` flag for ad-hoc deep dives (current output is intentionally terse for default).

---

## Quick orientation when you reopen

1. `cd C:\dev\workspace\MyFinanceView`
2. `git status` — confirm clean
3. `git log --oneline -10` — see what landed
4. `cat TOMORROW.md` — this file
5. Pick item from section above; start with #1 or #2 for low-friction warmup

Have a good night.
