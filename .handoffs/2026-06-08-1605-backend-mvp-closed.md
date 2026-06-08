---
created: 2026-06-08T16:05:00Z
branch: feat/backend-mvp-readonly
worktree: .claude/worktrees/backend-mvp-readonly
mode: paused
---

# backend-mvp-readonly — change archived + canonical spec synced; branch ready for operator PR

## Next step
Operator decides: open the PR (`gh pr create -B main` from this worktree's branch) OR start the next change `frontend-swap-to-backend` to make the deployed Vercel app stop hitting Supabase directly (currently 403) and call `/api/v1/*` instead.

## Goal
Close the OpenSpec change `backend-mvp-readonly`: archive the change directory + promote its delta spec to canonical. Hand the branch off in a clean state for the operator to ship.

## Done this session
- Commit `fa5ed4e spec(backend-mvp): archive change + sync canonical backend-rest-api spec` — git-mv'd `openspec/changes/backend-mvp-readonly/` → `openspec/changes/archive/2026-06-08-backend-mvp-readonly/`, created `openspec/specs/backend-rest-api/spec.md` (10 Requirements / 56 Scenarios verified 1:1 against the delta), flipped 8 close-out checkboxes (§10.1–10.5 operator gate, §12.1–12.3 closure), carried prior 12:36 handoff into branch history.
- Sync done by Sonnet subagent; spot-checked against delta — subagent's "9 req / 46 scn" report was a miscount, actual is 10/56, no content lost.
- Active changes after archive: only `supabase-backup-policy` remains.

## Working tree state
- **Committed (this session):** `fa5ed4e spec(backend-mvp): archive change + sync canonical backend-rest-api spec`, `5b1219c chore(handoff): close-out note for backend-mvp-readonly archive`, `cddf624 spec(backend-mvp): flip 8 close-out checkboxes in archived tasks.md`.
- **Staged:** none.
- **Unstaged:** none.
- **Untracked:** none.
- **Red tests:** none. Did not re-run suite; no code touched, only spec/docs moves.

## Pending
- **§12.4–12.5 of archived tasks.md (operator-gated):** push branch + `gh pr create -B main` + merge. Push policy on this project is local-only by default — operator must initiate.
- **§12.8 Notion sync:** mark TASK-BE-03, TASK-BE-04, TASK-BE-05, TASK-BE-06, TASK-DB-01 (display_name), TASK-DB-04 (merchants) done.
- **§9.5 OpenAPI 3.1 CLI validation:** deferred — smoke test OK, no CLI installed on host. Future change `api-spec-ci-gate` covers this.
- **Next OpenSpec changes queued** (all listed in §13 of archived tasks.md): `frontend-swap-to-backend`, `backend-deploy-{target}`, `api-spec-ci-gate`, `flyway-runtime`, `merchant-management-ui`, `backend-mvp-merchant-backfill`.

## Blockers
- none.

## Non-obvious context
- **Canonical spec count discrepancy was a subagent reporting miscount, not a sync defect.** Always cross-check req/scn counts via `grep -cE '^### Requirement: '` and `grep -cE '^#### Scenario: '` against both delta and canonical files before trusting a sync subagent's summary.
- **`git mv` produces `R`-status entries** that `git diff --stat` collapses cleanly — preserves rename detection across `openspec/changes/<id>/` → `openspec/changes/archive/YYYY-MM-DD-<id>/`. Do not use `rm` + `cp`; you lose history.
- **`git mv` after an unstaged Edit yields `RM` status** — the rename gets staged automatically, but the modification on top does NOT. `git commit -m` will silently commit only the rename, leaving the edit unstaged. Before archive-commit, always run `git status --short` and explicitly `git add` any `RM` entries at their NEW path. This bit this session — required addendum commit `cddf624`.
- **`tasks.md` for archived changes still carries open `[ ]` checkboxes** for items that genuinely deferred (e.g. operator-gated PR/merge, follow-up changes in §13). This is the convention — the archived tasks.md is a frozen snapshot, not a TODO list. The 12 remaining unchecked items are intentional.
- **`openspec status --change <id> --json` only validates artifact presence** (proposal/design/specs/tasks files exist), not task-checkbox completion. A change can report `isComplete: true` while still having `[ ]` tasks. Check both before archive.
- **First sync of a brand-new capability** creates `openspec/specs/<capability>/spec.md` with an H1 + Purpose block. Convention (per `backend-runtime/spec.md`): Purpose may be auto-generated "TBD — created by archiving change X" placeholder, or real content if the implementer writes it. This spec got real content because the capability is fully implemented.
- **`subagent_type: backend-developer` still does not resolve at runtime** as of this session — confirmed by prior handoff. Use `subagent_type: general-purpose` + explicit `model: "sonnet"` for execution tasks (saves ~2-3× vs Opus inherited from parent). The sync subagent ran on Sonnet per this policy.
