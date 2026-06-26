---
name: "Session Handoff"
description: Produce a structured handoff note for the next session (PRODUCE mode) or resume from the latest handoff (RESUME mode).
category: Workflow
tags: [workflow, session, handoff, resume]
---

Invoke the `session-handoff` skill and apply it now.

**Mode selection** (the skill itself documents these — quick guide):

- **PRODUCE** (default at end of session): If `$ARGUMENTS` is empty OR contains words like "stop", "wrap", "pause", "preparar", "paremos", "handoff", "save" — produce a new handoff document under `.handoffs/<YYYY-MM-DD-HHMM>-<slug>.md`.
- **RESUME**: If `$ARGUMENTS` contains "resume", "continuar", "retomar", "retomemos", "where", "donde", "pickup", "continue" — find the latest `.handoffs/*.md`, verify drift against current working tree, and post the resume summary.

**Arguments**: `$ARGUMENTS` may also contain a short slug or title to use for the filename in PRODUCE mode (e.g. `/session-handoff jwt-auth-wrap`). If omitted, derive a slug from the current branch or the session's main topic.

**Steps**

1. Load and follow the `session-handoff` skill from `.claude/skills/session-handoff/SKILL.md` (project) or `~/.claude/skills/session-handoff/SKILL.md` (global). The skill is the source of truth — do not paraphrase its rules.
2. Choose mode per the rules above (or per the skill's "When to use" section if ambiguous).
3. Execute that mode end-to-end per the skill, including:
   - Real `git status` / `git log` / `git diff --stat` calls (never improvise state).
   - If a harness feature is active for this work, also update `progress/current.md` + `feature_list.json` per the skill's Step 4.
4. Report back per the skill's Step 5 (PRODUCE) or post the ≤120-word resume summary and stop (RESUME).

Do not commit the handoff automatically — leave staging/commit to the user (or to a follow-up `/commit`).
