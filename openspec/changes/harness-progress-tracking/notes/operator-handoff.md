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

## Tasks 3.3 and 3.4 — SessionStart hook live verification

These require closing this Claude Code session and opening a fresh one — the implementing subagent cannot do that.

### 3.3 — Confirm preflight output appears in the first turn

1. After this implementation session ends, the operator opens a new Claude Code session in this repo (or the parent worktree).
2. Look at the very first system/context block: it should include the preflight output (one line per `[OK]`/`[WARN]`/`[FAIL]`/`[SKIP]` check and the `=== preflight: ... ===` summary line).
3. If the output is missing: check `.claude/settings.json` (or `settings.example.json`) for the `hooks.SessionStart` entry and confirm `scripts\preflight.ps1` is on disk at the worktree root.

### 3.4 — Fallback path (`pwsh` absent → `powershell.exe` runs)

Easiest cross-check: the hook command is `pwsh ... 2>nul || powershell.exe ...`. If `pwsh` is not installed AT ALL on this machine, the fallback branch runs every session — the simple existence of preflight output in step 3.3 already proves the fallback works.

To explicitly stress-test the fallback once `pwsh` IS installed:

```powershell
# 1. Rename pwsh.exe temporarily
$pwshPath = (Get-Command pwsh).Source
Rename-Item $pwshPath "$pwshPath.disabled"
# 2. Open a fresh Claude Code session, confirm preflight output still appears
# 3. Restore
Rename-Item "$pwshPath.disabled" $pwshPath
```

---

## Notes on `.claude/settings.json` being gitignored

The repo's `.gitignore` blocks `.claude/settings.local.json` (Claude Code local state) but `.claude/settings.json` itself is NOT in `.gitignore`. However, no `.claude/settings.json` was present on this worktree when the implementing subagent ran. The subagent therefore:

- Created `.claude/settings.json` with the SessionStart hook entry (this file IS git-tracked — operator must verify the commit includes it).
- Also committed `.claude/settings.example.json` as the canonical reference, so any future operator who has gitignored their own `.claude/settings.json` still has a documented template to copy from.

If the operator wants `.claude/settings.json` ITSELF to be gitignored locally (e.g. to layer personal hook entries on top), add `.claude/settings.json` to the local `.git/info/exclude` (not `.gitignore`) and copy `.claude/settings.example.json` into place manually.

---

## Quick checklist for the operator

- [ ] 2.10 — PS 5.1 smoke test passes locally
- [ ] 2.11 — PS 7 smoke test passes locally (install `pwsh` first if needed)
- [ ] 2.12 — Broken-compile mode produces `[FAIL]` and non-zero exit
- [ ] 2.13 — Missing-rclone mode produces `[SKIP]` and exits based on real failures
- [ ] 2.14 — Two-active-changes mode produces `[WARN]` and exit 0
- [ ] 3.3 — Fresh session shows preflight output as first-turn context
- [ ] 3.4 — Fallback `powershell.exe` path verified (either by `pwsh` being absent already, or by the explicit rename test)

Tick each in `tasks.md` after verifying. Once all are ticked, Section 6 (validate + adversarial-review + archive) becomes runnable.
