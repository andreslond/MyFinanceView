# .claude/SETUP.md — Reusable Claude Code Setup

Portable setup for the operator's Claude Code workflow: a worktree-aware
statusline with handoff-tuned context thresholds, the **session-handoff**
skill, and its **`/session-handoff`** slash command.

The skill + command + statusline are all **user-level** (live under
`~/.claude/`), so they work in every project once installed — but their
canonical source-of-truth lives in this repo so they survive devices,
re-installs, and minor refinements.

---

## 1. What this setup provides

- **`session-handoff` skill** (`.claude/skills/session-handoff/SKILL.md`) —
  produces a structured 7-section handoff note when ending a session
  mid-task and resumes from one when starting; persists under
  `.handoffs/<YYYY-MM-DD-HHMM>-<slug>.md` so handoffs travel with the
  branch.
- **`/session-handoff` slash command** (`.claude/commands/session-handoff.md`)
  — thin wrapper that routes between PRODUCE / RESUME modes based on
  arguments.
- **Worktree-aware statusline** (`.claude/statusline.ps1`) — shows
  `<repo>[wt:<worktree>]  <branch>  <model>@<effort>  ctx <tokens> (N%)`
  with color thresholds tuned to a 40% handoff trigger:
  - green `<25%`
  - yellow `25–39%` (prep handoff)
  - red `≥40%` (switch sessions / run `/session-handoff` now)

---

## 2. Install on a new device (or a fresh project)

Run from PowerShell. These commands are idempotent — re-running them
refreshes the user-level copies from whatever is currently in this repo.

### 2a. Skill + slash command (works in every project once installed)

```powershell
# From the repo root.
New-Item -ItemType Directory -Force "$env:USERPROFILE\.claude\skills"   | Out-Null
New-Item -ItemType Directory -Force "$env:USERPROFILE\.claude\commands" | Out-Null

Copy-Item -Recurse -Force `
  ".claude\skills\session-handoff" `
  "$env:USERPROFILE\.claude\skills\session-handoff"

Copy-Item -Force `
  ".claude\commands\session-handoff.md" `
  "$env:USERPROFILE\.claude\commands\session-handoff.md"
```

Verify: open a Claude Code session in any project and type `/session-handoff`
— it should appear in the slash-command list, and asking "preparemos un
handoff" should trigger the skill.

### 2b. Statusline

```powershell
# Copy the renderer next to the rest of the user-level Claude config.
Copy-Item -Force `
  ".claude\statusline.ps1" `
  "$env:USERPROFILE\.claude\statusline.ps1"
```

Then add (or replace) the `statusLine` block in
`~/.claude/settings.json` with:

```json
"statusLine": {
  "type": "command",
  "command": "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"C:\\Users\\Latmin\\.claude\\statusline.ps1\"",
  "padding": 0
}
```

> Replace `C:\\Users\\Latmin` with the actual `$env:USERPROFILE` of the
> target machine. The `\\` double-backslashes are required because this
> is JSON.

The statusline reads `effortLevel` directly from
`~/.claude/settings.json`, so changing `effortLevel` there
(e.g. `"xhigh"`, `"high"`, `"medium"`) is reflected immediately on the
next Claude Code prompt — no statusline restart needed.

---

## 3. Apply to a different project

This setup is intentionally split:

| Piece | Lives where | Reach |
|-------|-------------|-------|
| `session-handoff` skill (user copy) | `~/.claude/skills/session-handoff/` | All projects on the machine |
| `/session-handoff` command (user copy) | `~/.claude/commands/session-handoff.md` | All projects on the machine |
| Statusline | `~/.claude/statusline.ps1` + `settings.json` | All projects on the machine |
| Per-repo source-of-truth | this repo, `.claude/` | Travels with `git pull` |

So **on another project's repo you do not need to copy anything else**
— once the user-level install (step 2) is done, `/session-handoff`
works there, the skill auto-activates on trigger phrases, and the
statusline shows the same format.

If you want a project to *also* carry its own copies of the skill +
command (e.g. so collaborators get them on `git clone`), drop them
under that project's `.claude/skills/session-handoff/` and
`.claude/commands/session-handoff.md`. Project-level copies override
user-level in case of conflict.

---

## 4. Updating the setup

When this repo's `.claude/skills/session-handoff/SKILL.md`,
`.claude/commands/session-handoff.md`, or `.claude/statusline.ps1` are
edited and merged to `main`, re-run **step 2** on each device after
`git pull` to propagate the changes to the user-level copies. Nothing
auto-syncs — these are plain file copies.

A future improvement (not done today): symlink the user-level paths to
the in-repo files instead of copying. Requires Windows Developer Mode
or admin (`mklink /D ...`). Skipped for portability.

---

## 5. Where the skill writes handoffs

Handoffs go to **`.handoffs/<YYYY-MM-DD-HHMM>-<slug>.md`** in the
current repo's root (or worktree root if inside a worktree).

- **Default:** committed with the work branch so they travel between
  devices on `git pull` (this is what `feedback_branches_multiagent`
  expects in MyFinanceView).
- **Opt-out for local-only handoffs:** add `.handoffs/` to that
  project's `.gitignore`.

For projects that use the Uncle Bob harness, the skill ALSO updates
`progress/current.md` + `feature_list.json` per the harness progress
schema — see the skill's "Step 4" for the rule.
