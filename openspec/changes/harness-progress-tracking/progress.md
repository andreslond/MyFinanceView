<!--
  Live progress for openspec/changes/harness-progress-tracking/
  Schema: openspec/templates/progress-template.md
  Updated by: backend-developer subagent after every closed task
              (see .claude/agents/backend-developer.md).
-->

current_task: "6.1"
last_completed: "5.4"
next_step: "run `openspec validate harness-progress-tracking --strict` and fix any structural complaints (operator-gated entry into Section 6 — adversarial-review + archive)"
decisions_pending_design_update:
  - "scripts/preflight.ps1 uses ASCII `--` instead of `—` in line output because Write tool wrote the file UTF-8 no-BOM, and Windows PowerShell 5.1 mis-decodes em-dashes; ASCII keeps the script 5.1-safe per Decision 4."
  - ".claude/settings.json was absent on this worktree, so created from scratch with only the SessionStart hook entry. The `_comment_SessionStart` key documents the hook's purpose inline (Claude Code's settings.json accepts arbitrary unknown keys, so this is non-breaking; design.md Decision 5 only specified the hook command, not the file format)."
  - "docs/workflow.md gained a new section §0 'Session start' since there was no pre-existing 'Session start preamble' to update (task 4.2 said 'currently implicit'); the new section now explicitly documents the SessionStart hook + preflight output."
  - "Task 5 implemented BOTH the skill-patch path (5.2) AND the manual-fallback documentation (5.3). The /opsx:propose skill at .claude/commands/opsx/propose.md and .claude/skills/openspec-propose/SKILL.md is project-level (not user-level), so editing it is in scope; the docs fallback covers operators who bypass the slash command and call `openspec new change` directly. Open Question 1 in design.md is resolved: skill IS patched; manual step IS documented."
  - "Smoke test in 5.4 ran `openspec new change throwaway-test`, copied template, validated. The validate failed on missing deltas (proposal/design/tasks not yet created in an empty scaffold) — that's a CLI behaviour orthogonal to progress.md, not a regression we introduced. The template-copy step itself worked cleanly. Worth flagging to the operator: a future enhancement could be a `/opsx:propose-bare` that ONLY runs `openspec new change + template copy` for testing the seeding step in isolation."
blockers: []
last_updated: "2026-05-27T07:17:19Z"
