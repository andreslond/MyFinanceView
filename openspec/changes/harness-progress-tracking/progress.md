<!--
  Live progress for openspec/changes/harness-progress-tracking/
  Schema: openspec/templates/progress-template.md
  Updated by: backend-developer subagent after every closed task
              (see .claude/agents/backend-developer.md).
-->

current_task: "5.1"
last_completed: "4.5"
next_step: "inspect .claude/skills/openspec-propose/ and .claude/commands/opsx/propose.md to determine whether the propose flow supports a post-create template-copy step"
decisions_pending_design_update:
  - "scripts/preflight.ps1 uses ASCII `--` instead of `—` in line output because Write tool wrote the file UTF-8 no-BOM, and Windows PowerShell 5.1 mis-decodes em-dashes; ASCII keeps the script 5.1-safe per Decision 4."
  - ".claude/settings.json was absent on this worktree, so created from scratch with only the SessionStart hook entry. The `_comment_SessionStart` key documents the hook's purpose inline (Claude Code's settings.json accepts arbitrary unknown keys, so this is non-breaking; design.md Decision 5 only specified the hook command, not the file format)."
  - "docs/workflow.md gained a new section §0 'Session start' since there was no pre-existing 'Session start preamble' to update (task 4.2 said 'currently implicit'); the new section now explicitly documents the SessionStart hook + preflight output."
blockers: []
last_updated: "2026-05-27T07:14:21Z"
