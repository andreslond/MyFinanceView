---
created: 2026-07-01T05:37:25Z
branch: worktree-home-dashboard-demo
worktree: /home/claude/projects/MyFinanceView/.claude/worktrees/home-dashboard-demo
mode: blocked
---

# Home "Reflection-first" + Movimientos demo — build done, Vercel deploy pending

## Next step
Set Vercel project `frontend` **Root Directory = `frontend`**, then trigger a production deploy from `main` (repoId 1235604206) — dashboard Redeploy or API POST /v13/deployments — and curl the live bundle for markers `A dónde se fue tu dinero` / `Movimientos`.

## Goal
Ship a client-demo: mobile Home dashboard (donut/insight/trio, real June-2026 Supabase data) + infinite-scroll multi-bank "Movimientos", live on `frontend-delta-murex-29.vercel.app`.

## Done this session
- Feature built via TDD, all on `main` (pushed): `7c5ed02` and 4 prior (`89f35b3`,`b6a401c`,`ec23a6e`,`dea3654`). 23/23 vitest green, typecheck+lint+build clean.
- **Supabase 403 root-caused + FIXED live**: `myfinance` schema had no GRANTs for `authenticated`. Applied migration `grant_myfinance_select_to_authenticated` (usage+select+update transactions). Verified grants present.
- Vercel access solved: user's `vcp_…` token is write-capable (upload test 200); project `frontend`=`prj_3Spzp3Cz7fg0UovGHym9FsJKMdSJ`, team `team_Xt92MAE1SYF6JJElb6w1FfzB`.

## Working tree state
- **Committed (this session):** 5 commits, tree clean, `main`==`origin/main`==`7c5ed02`.
- Staged/Unstaged/Untracked: none (except this handoff).
- Red tests: none.

## Pending
- **Deploy** (the only blocker) — see Next step. Two prior attempts failed: (1) prebuilt dist upload → build ERROR (project runs a buildCommand; dist has no package.json); (2) fix = git deploy after Root Directory=frontend. A ready script existed at `$CLAUDE_JOB_DIR/tmp/deploy_git.mjs` (ephemeral — reconstruct from facts below).
- Add Flyway **V006** migration in `backend/database/migrations/` capturing the GRANT (mirror the live migration) — repo has NO grant statements today.
- After deploy: verify logged-in data loads (simulate via SQL `set local role authenticated; set request.jwt.claims '{"sub":"<user_id>","role":"authenticated"}'`).

## Blockers
- Needs a **fresh write-scoped `vcp_` Vercel token** (user pastes it; not stored here for security). The first `vck_…` token they gave was READ-ONLY (403 on write) and its account is empty.

## Non-obvious context
- Vercel deploy facts: project `frontend` git-linked to `andreslond/MyFinanceView` repoId **1235604206** prodBranch **main**, but **Root Directory=None** → build looks for package.json at repo root (absent) → that is why pushes never auto-deployed. Set it to `frontend`. Env vars `VITE_SUPABASE_URL`/`VITE_SUPABASE_ANON_KEY` already on the project. Vercel account = andrestor2@gmail.com, team slug `andrestor2-gmailcoms-projects`.
- The console errors the user saw are the OLD build (`index-VQ-NSkkJ.js`): legacy `categoriesService` selects non-existent `parent_id`→400; `TxRow` does `amount.startsWith` but PostgREST returns `amount` as a **number**→TypeError. NEW code avoids both (no parent_id; `Number.parseFloat`). Deploying new build fixes them.
- Demo month pinned **June 2026** (July empty). Trio shown honest: `saved` negative because income is mis-categorized in the data (user fixes data later, screen recomputes live on refresh — no realtime).
- Worktree is harness-owned (`.claude/worktrees/`) — do NOT `git worktree remove`; use ExitWorktree.
- User directive when interrupted: "revisa y soluciona en bucle hasta que esté todo perfecto para la demo" — autonomous deploy→verify→fix loop is authorized.
