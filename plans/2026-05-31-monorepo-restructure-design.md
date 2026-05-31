# Monorepo Restructure — Move Backend Under `backend/`

**Date**: 2026-05-31
**Branch**: `refactor/monorepo-backend-move`
**Status**: Implementation in progress (this doc lands as commit 0 of the PR)
**Type**: Refactor (commit-direct, no OpenSpec ceremony — see §2)

---

## 1. Goal

Consolidate every backend-only file under a top-level `backend/` directory so the repo root is free to host a sibling `frontend/` and other cross-cutting infrastructure as the project grows beyond a single Spring Boot service.

**In scope**:
- `git mv` the backend code (`pom.xml`, `mvnw*`, `.mvn/`, `src/`, `database/`) under `backend/`.
- Update every reference to the old paths in docs, scripts, CI, docker-compose, and OpenSpec capability specs.
- Remove the obsolete `TOMORROW.md` artefact.

**Out of scope** (deferred to separate work):
- Scaffolding the frontend project (placeholder `frontend/` directory stays empty).
- Moving `scripts/backup/` (introduced by the in-flight `supabase-backup-policy` change).
- Rebasing the divergent branches `feat/supabase-backup-policy-impl`, `feat/flyway-migrations`, `supabase-cuts` against the new layout.
- Adopting an `infra/` directory or reorganising `docker/`, `scripts/`, or `.claude/`.

---

## 2. Why a direct commit + PR, not an OpenSpec change

The OpenSpec workflow is reserved for changes with non-obvious design decisions, adversarial-review-worthy trade-offs, or behavioural shifts that need a delta-spec trail. This refactor has none of that: it is a mechanical `git mv` plus path updates whose target structure was already documented in `SPEC.md` ("Sección 4 — Estructura del proyecto").

Forcing this change through `/opsx:propose` would generate `proposal.md`, `design.md`, `tasks.md`, `specs/`, and an archive entry whose content would essentially repeat what `git log -p` already shows. The HITL gates (A/B/D/C) add no signal here because there is no spec to debate.

If a future contributor needs the audit trail, this design doc plus the PR description provide it. Behavioural OpenSpec capability specs (`openspec/specs/backend-runtime/spec.md`) are updated as part of commit 2 to reflect the new working-directory.

---

## 3. Target structure

```
myfinance-view/
├── backend/                       ← Java/Spring Boot self-contained
│   ├── pom.xml
│   ├── mvnw, mvnw.cmd, .mvn/
│   ├── src/{main,test}/java/com/myfinanceview/
│   └── database/{init-db.sh, local/, migrations/}
├── frontend/                      ← Empty placeholder, future React
├── docker/docker-compose.yml      ← Stays in root, mounts ../backend/database/*
├── docs/                          ← Cross-cutting standards
├── openspec/                      ← Spec workflow
├── plans/                         ← Per-feature plans (this doc lives here)
├── scripts/                       ← preflight.ps1 and future cross-cutting helpers
├── .claude/, .github/             ← AI harness + CI
└── SPEC.md, CLAUDE.md             ← Project north star + AI entrypoint
```

### Why `backend/` (not `apps/backend/`, not `services/backend/`)
A flat `backend/` is the simplest naming that reads correctly for a project that today has exactly one backend service. If the project ever sprouts a second service that needs sibling-of-backend status, we can rename in one commit.

### Why `docker/` stays in root
Compose configs are cross-cutting (the future frontend dev workflow may also need them). Mount paths now use `../backend/database/...` (verified at runtime — see §5).

### Why `database/` moves to `backend/database/`
The init scripts and migrations are coupled to the backend's data access layer; the only consumer is Spring Boot via Testcontainers and the local Postgres compose stack. Keeping them under `backend/` preserves locality.

---

## 4. Execution — 4 commits in this PR

| # | Commit | Contents |
|---|---|---|
| 0 | `docs(plans): add monorepo-restructure design doc` | This file |
| 1 | `refactor(monorepo): move backend code under backend/` | 27 `git mv` renames: pom, mvnw, .mvn, src, database. Working-tree modifications to `init-db.sh` and `PostgresTestcontainerTest.java` deferred to commit 2 to keep this commit a pure move |
| 2 | `refactor(monorepo): update paths in docs, scripts, CI, docker` | `.github/workflows/ci.yml` (working-directory: backend), `SPEC.md`, `docker/docker-compose.yml`, all `docs/*.md` references, both `openspec/specs/*` files, `plans/savings-goals-plan.md`, `scripts/preflight.ps1`, plus the deferred working-tree mods from commit 1 |
| 3 | `chore: remove TOMORROW.md` | Cleanup of the temporary handoff artefact from the previous sprint |

Each commit should leave `mvn verify` green; bisect-friendly.

---

## 5. Pre-commit verification (executed 2026-05-31)

| Check | Command | Result |
|---|---|---|
| `mvn compile` (from `backend/`) | `cd backend; ./mvnw -q compile` (via preflight) | OK |
| Full `mvn verify` (Testcontainers) | `cd backend; ./mvnw -B verify` | BUILD SUCCESS in 01:48 — 5/5 tests green (`PostgresTestcontainerTest` 1/1, `MyFinanceViewApplicationTests` 4/4) |
| Docker compose smoke | `docker compose -f docker/docker-compose.yml down -v && up -d` | Init pipeline ran V000 stubs + V001..V003 from `backend/database/*` mounts; `myfinance.categories` = 19, `myfinance.banks` = 10, 8 tables in `myfinance` schema |
| Grep for orphaned old paths | `database/(migrations\|local\|init-db)` outside `backend/`, `./mvnw` outside `backend/` and CI | All hits in archived OpenSpec changes (immutable history) or correctly updated |

---

## 6. Out-of-scope follow-ups (documented for future work)

### Three divergent branches will need rebase work
These branches were cut before the design-system bootstrap (commit `843b6ae`) AND before this refactor. Merging any of them as-is would revert main:

| Branch | Adds | Risk on rebase |
|---|---|---|
| `feat/supabase-backup-policy-impl` (`b98d054`) | `scripts/backup/runner/, workers/, n8n/, traefik/...` (~93 files, +3140 / -7971) | **High** — deletes `scripts/preflight.ps1`, all of `docs/design/raw/**`, `TOMORROW.md`, and modifies `.claude/agents/*` with older versions |
| `feat/flyway-migrations` (`8d84bd8`) | Flyway integration + `FlywayMigrationIntegrationTest` (~85 files, +958 / -9303) | **High** — same out-of-date deletions; additionally references old `database/` path that this PR moves |
| `supabase-cuts` (`52780b0`) worktree | Adversarial-review-driven spec cuts for `supabase-backup-policy` v3 | **Medium** — may already be merged via spec edits in main; verify before deleting |

Plan: address each branch in a dedicated session once `supabase-backup-policy` is unblocked operationally (see `openspec/changes/supabase-backup-policy/`).

### Two stale worktrees
- `.claude/worktrees/harness-progress-tracking` — change already archived; `git worktree remove` is safe.
- `.claude/worktrees/agent-a4fc9f0f4a65accdd` — locked orphan; investigate-then-remove.

### `frontend/` remains an empty placeholder
No `package.json`, no build tool decision yet. When the React frontend kicks off, that decision lives in `docs/frontend-standards.md` (currently a placeholder) and will likely become its own OpenSpec change.

---

## 7. Definition of Done

- [x] `mvn verify` green from `backend/` (5/5 tests)
- [x] Docker compose init pipeline green with new mounts (V000 + V001..V003)
- [x] Static grep shows no orphaned old paths in live (non-archive) files
- [ ] 4 commits pushed to `origin/refactor/monorepo-backend-move`
- [ ] PR opened with scope explicit in description
- [ ] CI green (`./mvnw verify` from `working-directory: backend`)
- [ ] PR merged to `main`
- [ ] Post-merge `scripts/preflight.ps1` reports 0 refactor-related warns
- [ ] Worktree `.claude/worktrees/harness-progress-tracking` removed
- [ ] Divergent branches noted in this doc (§6) for future rebase work
