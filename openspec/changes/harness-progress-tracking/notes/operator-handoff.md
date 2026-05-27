# Operator handoff — `harness-progress-tracking`

The following tasks in `tasks.md` are flagged `(OPERATOR ACTION — see notes/operator-handoff.md)` because they require running commands on the operator's actual Windows 10 PC (PS 5.1 vs `pwsh` 7), opening a fresh Claude Code session, or temporarily breaking/renaming local tools. The implementing subagent cannot reach those side effects safely; the operator runs them and ticks the boxes.

Run these in order, from the worktree root: `C:\dev\workspace\MyFinanceView\.claude\worktrees\harness-progress-tracking`.

---

## Task 2.10 — Smoke-test `scripts/preflight.ps1` in Windows PowerShell 5.1

```powershell
cd C:\dev\workspace\MyFinanceView\.claude\worktrees\harness-progress-tracking
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\preflight.ps1
$LASTEXITCODE
```

**Expected:** every check emits one line tagged `[OK]`/`[WARN]`/`[FAIL]`/`[SKIP]`. The final `=== preflight: N passes, … ===` line prints. `$LASTEXITCODE` equals the number of `[FAIL]` lines (likely 0 in a healthy repo).

A non-interactive run inside this implementation session already produced:

```
[WARN]  active changes -- 2 active changes -- confirm intentional parallel work (...)
[OK]    mvn compile
[WARN]  working tree -- N modified/untracked files -- ...
[OK]    git head -- harness-progress-tracking @ <hash> ...
[OK]    harness-progress-tracking artefacts -- complete
[OK]    supabase-backup-policy artefacts -- complete
[SKIP]  supabase backup freshness -- rclone not installed -- ...
=== preflight: 4 passes, 2 warns, 1 skips, 0 fails ===
ExitCode: 0
```

Operator confirms the same shape on their machine and ticks the box.

---

## Task 2.11 — Smoke-test `scripts/preflight.ps1` in PowerShell 7 (`pwsh`)

```powershell
cd C:\dev\workspace\MyFinanceView\.claude\worktrees\harness-progress-tracking
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts\preflight.ps1
$LASTEXITCODE
```

If `pwsh` is not on PATH (`Get-Command pwsh -ErrorAction SilentlyContinue` returns nothing), install via `winget install --id Microsoft.PowerShell` first. The script is written to PS 5.1 conventions, so PS 7 should behave identically — same line shape, same exit code.

---

## Task 2.12 — Smoke-test failure mode

Temporarily break the project's compile and confirm preflight reports `[FAIL]` AND keeps running subsequent checks AND exits non-zero.

```powershell
# 1. Pick a Java file with imports — e.g. the app's main class
$file = Get-ChildItem -Path src\main\java -Recurse -Filter *.java | Select-Object -First 1
# 2. Append a deliberately invalid import line
Add-Content -Path $file.FullName -Value 'import nonexistent.package.Nothing;'
# 3. Run preflight
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\preflight.ps1
# Expect: [FAIL] mvn compile failed -- <stderr tail>, then remaining checks still run,
#         then === preflight: ... 1 fails ===, then $LASTEXITCODE = 1
$LASTEXITCODE
# 4. Restore — discard the change
git checkout -- $file.FullName
```

Operator verifies: (a) a `[FAIL]` line appears, (b) the script DID still run the post-compile checks (you see lines for working tree, git head, artefacts, backup), (c) exit code is non-zero.

---

## Task 2.13 — Smoke-test missing-`rclone` mode

The current machine almost certainly does not have `rclone` installed (the in-session smoke test already saw `[SKIP] supabase backup freshness -- rclone not installed`). If `rclone` IS installed locally, shadow it for this test:

```powershell
# Save current PATH, then mask rclone for this session only
$savedPath = $env:Path
$env:Path = ($env:Path -split ';' | Where-Object { -not (Test-Path "$_\rclone.exe") }) -join ';'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\preflight.ps1
$LASTEXITCODE
# Expect: [SKIP] supabase backup freshness -- rclone not installed, exit 0 (assuming no other FAILs)
# Restore PATH
$env:Path = $savedPath
```

If `rclone` is genuinely absent, the in-session run already produced the expected `[SKIP]` line — tick the box without further action.

---

## Task 2.14 — Smoke-test two-active-changes mode

The worktree currently HAS both `supabase-backup-policy` and `harness-progress-tracking` active. The in-session run already produced `[WARN] active changes -- 2 active changes -- confirm intentional parallel work (...)` AND exited 0. Operator confirms the same on their machine and ticks the box.

---

## Task 3a.5 — agent-invoked preflight smoke (Decision 5 v2)

**ROLLED FROM v1 hook-based smoke (formerly 3.3/3.4) to v2 directive-based smoke.** The SessionStart hook was removed; there is no `.claude/settings.json` in this repo. Preflight is now invoked by the agent following the directive in `CLAUDE.md ## Workflow (per change)`. See `design.md` Decision 5 v2 for the rollback rationale and `proposal.md ## Closing note` for the live state at archive time.

**How to smoke (passive — happens naturally):**

The next real `/opsx:apply` session in this repo IS the smoke. When the operator types `/opsx:apply <change>`, verify that Claude's first action is:

```
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/preflight.ps1
```

…and that the output appears in the chat (visible to the operator), NOT injected as silent additional context the way the v1 hook did. Tick task 3a.5 in `tasks.md` (or `openspec/changes/archive/<date>-harness-progress-tracking/tasks.md` after archive) after observing the behaviour.

**If the agent skips preflight** for a non-trivial session, the `adversarial-reviewer` agent will flag the omission as a Minor `process-tooling` finding at next review (per `.claude/agents/adversarial-reviewer.md` line 83). Catch yourself or rely on the reviewer.

---

## Quick checklist for the operator

- [ ] 2.10 — PS 5.1 smoke test passes locally (covered in-session 2026-05-27; operator re-verification optional)
- [ ] 2.11 — PS 7 smoke test passes locally (DEFERRED — install `pwsh` via `winget install --id Microsoft.PowerShell` first; see TOMORROW.md)
- [x] 2.12 — Broken-compile mode produces `[FAIL]` and non-zero exit (verified in-session 2026-05-27)
- [x] 2.13 — Missing-rclone mode produces `[SKIP]` and exits based on real failures (vacuously satisfied: rclone not installed; observed in 2.10 smoke)
- [x] 2.14 — Two-active-changes mode produces `[WARN]` and exit 0 (vacuously satisfied: two changes active right now; observed in 2.10 smoke)
- [ ] 3a.5 — Next real `/opsx:apply` session shows agent-invoked preflight in chat (natural smoke; tick after observation)

Tick each in `tasks.md` (or the archived `tasks.md` once `/opsx:archive` has run) after verifying.
