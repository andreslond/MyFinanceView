<!--
  Live progress for openspec/changes/harness-progress-tracking/
  Schema: openspec/templates/progress-template.md
  Updated by: backend-developer subagent after every closed task
              (see .claude/agents/backend-developer.md).
-->

current_task: "6.1"
last_completed: "3.4"
next_step: "run `openspec validate harness-progress-tracking --strict` and fix any structural complaints, then dispatch adversarial-review subagent for Gate D"
decisions_pending_design_update:
  - "scripts/preflight.ps1 uses ASCII `--` instead of `—` in line output because Write tool wrote the file UTF-8 no-BOM, and Windows PowerShell 5.1 mis-decodes em-dashes; ASCII keeps the script 5.1-safe per Decision 4."
  - ".claude/settings.json was absent on this worktree, so created from scratch with only the SessionStart hook entry. The `_comment_SessionStart` key documents the hook's purpose inline (Claude Code's settings.json accepts arbitrary unknown keys, so this is non-breaking; design.md Decision 5 only specified the hook command, not the file format)."
  - "docs/workflow.md gained a new section §0 'Session start' since there was no pre-existing 'Session start preamble' to update (task 4.2 said 'currently implicit'); the new section now explicitly documents the SessionStart hook + preflight output."
  - "Task 5 implemented BOTH the skill-patch path (5.2) AND the manual-fallback documentation (5.3). The /opsx:propose skill at .claude/commands/opsx/propose.md and .claude/skills/openspec-propose/SKILL.md is project-level (not user-level), so editing it is in scope; the docs fallback covers operators who bypass the slash command and call `openspec new change` directly. Open Question 1 in design.md is resolved: skill IS patched; manual step IS documented."
  - "Smoke test in 5.4 ran `openspec new change throwaway-test`, copied template, validated. The validate failed on missing deltas (proposal/design/tasks not yet created in an empty scaffold) — that's a CLI behaviour orthogonal to progress.md, not a regression we introduced. The template-copy step itself worked cleanly. Worth flagging to the operator: a future enhancement could be a `/opsx:propose-bare` that ONLY runs `openspec new change + template copy` for testing the seeding step in isolation."
  - "Initial .claude/settings.json had THREE bugs caught by Smoke C dogfooding (none of them caught by the implementer's in-session smokes): (1) wrong hook schema — needs `[{matcher: '', hooks: [{type, command}]}]` envelope, not `[{type, command}]` directly; (2) backslash escape — `scripts\\preflight.ps1` in JSON resolves to `scripts\\preflight.ps1` literal, which Git Bash (the shell Claude Code uses for hooks on Windows) collapses `\\p` to `p` producing `scriptspreflight.ps1`; (3) `2>nul` is cmd-syntax — Git Bash treats `nul` as a filename and creates a stray `nul` file each session. All three fixed in commit <pending>. Smoke C confirmed working via fresh Claude session quoting verbatim preflight context. Worth flagging in adversarial-review category `process-tooling`: implementer-level smoke tests cannot substitute for end-to-end dogfooding on the actual Claude Code runtime."
  - "Smoke B revealed a Minor diagnostic-quality issue in preflight.ps1 task 2.4: the captured stderr tail picks up Java 25 `sun.misc.Unsafe` deprecation warnings (which mvn emits to stderr BEFORE the actual compile error) instead of the actual error line. The [FAIL] tag and exit code are correct; only the human-readable detail is unhelpful. Follow-up polish: replace `tail -n 5` of stderr with `grep -E 'ERROR|error:' | tail -n 5`. Not blocking for v1."
blockers: []
last_updated: "2026-05-27T07:55:00Z"
