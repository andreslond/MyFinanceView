---
name: session-handoff
description: Use when a session is ending with work still in flight — produce a structured handoff note so a fresh session (tomorrow, another device, a new agent) can resume without re-reading the transcript. Also use at session start when the user says "continuing", "where were we", or "retomemos" — read the latest handoff and post a one-paragraph resume summary before acting.
author: MyFinanceView
version: 1.0.0
---

# session-handoff Skill

## Overview

A **session handoff** is a short, structured note that lets the next session pick up cold. Goal: stop saturating context by re-reading transcripts. The note is **focused** — what was done, where the working tree is, the single concrete next step, and any non-obvious context that would be lost otherwise.

**Core principle:** A handoff is a *note*, not a transcript. If it can't be read in 60 seconds, it's wrong.

## When to use

**PRODUCE mode** (end of session):
- User says "let's stop here / preparemos un handoff / wrap up / paremos por hoy"
- Context window is filling up mid-task and work is incomplete
- About to `/compact`, `/clear`, switch worktrees, or close the IDE
- Switching devices (laptop → desktop) with WIP

**RESUME mode** (start of session):
- User says "continuemos / where were we / retomemos / pick up from yesterday"
- Working directory has a recent `.handoffs/*.md` (within last 7 days) and current task is unclear
- User explicitly invokes the skill at session start

## When NOT to use

- Trivial Q&A sessions (no edits, no decisions) — no handoff needed.
- Work that's already complete and committed — the commit message + PR description IS the handoff.
- Work tracked as an active harness feature with a healthy `progress/current.md` — **update `progress/current.md` and `feature_list.json` instead** (the project's canonical resume mechanism per `CLAUDE.md`). The handoff note is for work *outside* the harness pipeline (exploration, infra fixes, scratch sessions) OR a complement when the harness session also had non-pipeline scratch work.

## Output location

Write to: `.handoffs/<YYYY-MM-DD-HHMM>-<short-slug>.md` in the repo root.

- Timestamp is UTC, format `YYYY-MM-DD-HHMM` (e.g. `2026-06-02-2145`).
- Slug is 2–4 words, kebab-case, describes the work (e.g. `jwt-auth-middleware`, `supabase-backup-cuts`).
- Create the `.handoffs/` directory if missing.
- **Commit the handoff with the work branch** so it travels with the branch (other devices get it on `git pull`). If the user prefers local-only handoffs, they add `.handoffs/` to `.gitignore` — do not assume.
- If working in a git worktree under `.claude/worktrees/<name>/`, write `.handoffs/` inside that worktree (so it's on the worktree's branch). Do NOT write into the main checkout from a worktree session.

## Mode A: PRODUCE — write the handoff

### Step 1: gather state (do NOT improvise it)

Run these and read the actual output before writing the note. Do not write the handoff from memory:

```powershell
git status --short
git log --oneline -10
git diff --stat
git branch --show-current
```

If working in a worktree (cwd under `.claude/worktrees/`), note the worktree path too.

### Step 2: write the document using this schema

```markdown
---
created: <ISO-8601 UTC, e.g. 2026-06-02T21:45:00Z>
branch: <git branch name>
worktree: <path or "main checkout">
mode: in-flight | paused | blocked
---

# <Short title — what this session was about>

## Next step
<ONE sentence. ONE concrete action. The very first thing the next session should do.>
<If blocked, this sentence names what unblocks it.>

## Goal
<1–3 sentences. What were we trying to accomplish overall?>

## Done this session
- <bullet — what landed, with commit hash if committed>
- <bullet — include test status: "6 unit tests green" not "tests work">

## Working tree state
- **Committed (this session):** `<hash> <subject>` × N
- **Staged:** `<path>` — <one-line what's in it>
- **Unstaged:** `<path>` — <one-line what's in it and whether it compiles>
- **Untracked:** `<path>` — <why it exists>
- **Red tests:** `<test name>` — <one-line failure mode and root cause if known>

## Pending
- <queued item not yet started>
- <open decision needing operator input, with options listed>
- <open question, even if vague>

## Blockers
- <external thing we're waiting on; empty list if none>

## Non-obvious context
<Things a fresh agent or a tomorrow-you would NOT figure out from the diff alone:>
- <non-obvious decision made this session, and why>
- <project convention you discovered the hard way — link to memory file or doc>
- <gotcha that bit us and how we worked around it>
```

### Step 3: rules for filling it in

| Section | Rule |
|---------|------|
| **Next step** | EXACTLY one sentence. Not a checklist. If you want to list more, those go in **Pending**. |
| **Done this session** | Verifiable claims only. "Tests green" requires a hash or file. No vague "made progress on X". |
| **Working tree state** | Mirror `git status` exactly — do not paraphrase. Wrong git state is worse than missing git state. |
| **Non-obvious context** | If it's in the diff or in a commit message, it does NOT belong here. Only what would be LOST. |
| **Length** | Whole document under 60 lines. Trim aggressively. A handoff that takes longer to read than to write is broken. |

### Step 4: if working on a harness feature, also update `progress/current.md`

If `progress/current.md` exists and matches this session's work:

1. Refresh the feature's status, `last_completed`, `next_step`, `last_updated`.
2. Update `feature_list.json` if the feature phase changed (e.g. `in_progress` → `done`).
3. Reference the handoff in `next_step` if it carries detail that doesn't fit the schema: `next_step: "see .handoffs/2026-06-02-2145-jwt-auth.md"`.

The handoff and `progress/current.md` are complementary: `progress/current.md` is the structured pointer the harness reads; the handoff is the prose with the non-obvious context.

### Step 5: report back

After writing, tell the user:
- The file path written.
- ONE line of what's in **Next step**.
- Whether you also touched `progress.md`.
- Whether the handoff is committed yet (default: not committed — let the user run `/commit` or stage manually).

## Mode B: RESUME — read the latest handoff

### Step 1: locate the handoff

```powershell
Get-ChildItem .handoffs -Filter *.md | Sort-Object LastWriteTime -Descending | Select-Object -First 3
```

If there's no `.handoffs/` directory or it's empty: tell the user, then ask whether they want to resume from `progress.md` (if an OpenSpec change is in flight) or start fresh.

### Step 2: read the most recent file

Read the full handoff. Check the `branch:` field against `git branch --show-current` — if they differ, surface that BEFORE doing anything else (the user may be on the wrong branch / worktree).

### Step 3: post a "resuming from" summary

Write ONE paragraph to the user (≤120 words) covering:

1. Date and short title of the handoff being resumed.
2. The single **Next step** from the handoff.
3. Any **Blockers** that are still unresolved (verify by checking; don't assume the handoff is right).
4. Branch + worktree mismatch warning if relevant.
5. Whether the working tree currently matches what the handoff says (run `git status`; flag drift).

Then stop and wait for the user to confirm before acting. The user may correct stale assumptions.

### Step 4: verify before acting

A handoff is a *claim about state*, not state itself. Before recommending the next action:
- If the handoff says a file is "unstaged with TODO" → check the file is still unstaged and still has the TODO.
- If the handoff says a test is RED → run it.
- If the working tree drifted (user committed/reset/stashed between sessions) → re-read the relevant files, do not trust the handoff for what's on disk.

If state drifted significantly, say so explicitly and ask whether to update the handoff before proceeding.

## Common mistakes

| Mistake | Why it's wrong | Fix |
|---------|----------------|-----|
| Transcript-style narrative ("we tried X, then Y…") | Defeats the purpose; next session re-reads as much as the original chat | Bullet points, verbs in past tense, no causality narration |
| Multiple "next steps" | The next session can't pick *one* without re-deciding | Single sentence in **Next step**; everything else under **Pending** |
| Paraphrased git state | If wrong, agent acts on wrong tree → silent damage | Copy `git status` output literally; do not interpret |
| Missing **Non-obvious context** | Tomorrow-you forgets the gotcha and rediscovers it the hard way | Always include even if it feels trivial; if truly empty, write `- none` |
| Saving outside `.handoffs/` (project root, `docs/`, etc.) | RESUME mode won't find it | Always `.handoffs/<timestamp>-<slug>.md` |
| Skipping `progress/current.md` when in a harness feature | Splits the source of truth | Update both; cross-link via `next_step` |
| RESUME without verifying drift | Acts on a stale snapshot, breaks user's WIP | Always run `git status` and reconcile before recommending anything |
| Producing a handoff from a worktree but writing into the main checkout | Handoff doesn't travel with the branch; other devices won't see it on pull | Always write `.handoffs/` relative to current cwd, which is the worktree root |

## Red flags — STOP

If you find yourself thinking any of these while in PRODUCE mode, the handoff is wrong:

- "I'll just dump everything we discussed" → No. Filter to what's needed to resume.
- "Let me make this comprehensive" → No. ≤60 lines. Trim.
- "The next session can figure out the next step from context" → No. ONE sentence. Be specific.
- "I'll write it from memory, faster than running `git status`" → No. State claims must come from real commands.

If in RESUME mode:

- "The handoff said X so I'll do X" → No. Verify state first, then act.
- "I'll act now and surface the handoff later" → No. Summary + wait first.

## Quick reference

| Task | Command / action |
|------|------------------|
| Find latest handoff | `Get-ChildItem .handoffs -Filter *.md \| Sort-Object LastWriteTime -Descending \| Select-Object -First 1` |
| Read schema | This SKILL.md, "Step 2: write the document using this schema" |
| Write a handoff | Use the Write tool to `.handoffs/<YYYY-MM-DD-HHMM>-<slug>.md` |
| Sync harness progress | Update `progress/current.md` + `feature_list.json` per the harness schema |
| Verify state on resume | `git status --short && git log --oneline -10` |
