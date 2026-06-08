# Adversarial review — supabase-backup-policy replant — PASS 3 (2026-06-01)

Reviewer: adversarial-reviewer subagent, Opus 4.7 (1M context), invoked from
the worktree at `D:\dev\workspace\MyFinanceView\.claude\worktrees\supabase-replant`.

Scope (per the pass-3 brief): re-check the TWO Major fixes applied in this
session (M2 Daily.json IF-node simplification, M3 errorWorkflow re-link
documentation at tasks 8.4a); hunt for new issues introduced by those fixes;
regression-check the previously-fixed B1/B2/M1/N1/N2/Q1. Out-of-scope
adversaries (per `proposal.md ## Threat model`) and deferred follow-ups
(m1-m6, Q2, Q-N1, partial M4 sweep) NOT re-litigated.

---

## Verdict
**PASS**

The two Major fixes authorised this session are correctly implemented and
introduce no new code-vs-spec drift or orphan references. M2's
simplification of `MyFinanceBackup-Daily.json` (drop the disconnected IF
node + drop `neverError: true` so a non-2xx response throws and the
existing `errorWorkflow` binding fires the ErrorHandler) is structurally
clean: the file parses, has exactly the expected 2 nodes + 1 connection
edge, and `errorWorkflow: "MyFinanceBackup-ErrorHandler"` is preserved.
M3's new task 8.4a covers all three workflows that have errorWorkflow set
(Daily, PreOp, Watchdog — matches grep across `scripts/backup/n8n/`),
explains the why, and includes a verification step. No spec scenario in
`specs/database-backups/spec.md` references the dropped `Check HTTP Status`
node or `neverError: true`, so no orphan spec drift was introduced.

No new findings raised. All previously-fixed Blockers/Majors/Minors hold.
The previously deferred operator-decision follow-ups (m1-m6, Q2, Q-N1)
remain documented in `progress.md:14-15` and the prior review reports.

---

## Pass-2 + new-fixes status

### M2 (Daily.json IF-node): **FIXED**

`scripts/backup/n8n/MyFinanceBackup-Daily.json` (now 66 lines, was ~110):

- `node -e JSON.parse(...)` parses cleanly.
- `nodes` array contains exactly TWO entries: `Schedule Trigger`
  (`n8n-nodes-base.scheduleTrigger`, typeVersion 1.1, cron `30 2 * * *`,
  timezone `America/Bogota`) and `POST /run/daily`
  (`n8n-nodes-base.httpRequest`, typeVersion 4.1).
- `connections` map has EXACTLY one outgoing edge:
  `Schedule Trigger → POST /run/daily`. No `Check HTTP Status` entry
  remains anywhere in the file.
- HTTP node `parameters.options` is `{"timeout": 1200000}` — NO
  `neverError`, NO `fullResponse`, NO `response.response.*` wrapping.
  This is the correct shape: n8n's `httpRequest` typeVersion 4.1
  default behaviour on a 4xx/5xx is to throw, so the workflow-level
  errorWorkflow binding fires.
- `settings.errorWorkflow: "MyFinanceBackup-ErrorHandler"` preserved at
  line 63 — the design intent (M3 binding handles non-2xx) survives.
- No orphan references to `Check HTTP Status` or `check-http-status`
  anywhere in the worktree code/config — grep returns matches only in
  `progress.md:31` (correctly framed as "dropped IF Check HTTP Status…")
  and `notes/adversarial-review-replant.md` (the historical pass-1
  report, untouched as an audit trail). No README, tasks.md, or spec
  scenario references the removed node.

The original M2 design intent ("ErrorHandler is the canonical
workflow-level error path") is now actually achievable end-to-end via
the simpler "let the HTTP node throw on non-2xx → errorWorkflow fires"
path, provided the operator follows task 8.4a to re-link the binding by
ID. Without 8.4a, the binding is silent — but that is the M3 concern,
not a regression of M2.

### M3 (errorWorkflow re-link doc): **FIXED**

`openspec/changes/supabase-backup-policy/tasks.md:185` (the new task 8.4a):

- Correctly positioned immediately after 8.4 (import workflows) and
  before 8.5 (Kuma push URL reachability check). The logical order is
  right — import first, then re-link errorWorkflow before any smoke test.
- Unambiguous operator instructions: "open the workflow → Settings →
  Error Workflow dropdown → select `MyFinanceBackup-ErrorHandler` → Save."
- Covers ALL three workflows whose JSON carries
  `"errorWorkflow": "MyFinanceBackup-ErrorHandler"` —
  verified by `grep errorWorkflow scripts/backup/n8n/`:
  Daily.json:63, PreOp.json:125, Watchdog.json:170. Task 8.4a lists
  "Daily, PreOp, and Watchdog" — exact match.
- Includes a verification step: intentionally break `BACKUP_DB_PASSWORD`,
  confirm `MyFinanceBackup-ErrorHandler` shows in the n8n Executions
  list with the failing parent's data.
- Explains the WHY in operator-readable language: "n8n binds
  errorWorkflow by numeric ID — a bare name string is silently ignored
  in most n8n versions."
- Includes the failure-mode footnote: "Without this step, n8n-side
  failures produce no ErrorHandler alert (the Kuma dead-man-switch
  still catches workflow non-execution, but ErrorHandler-only paths go
  silent)" — accurately scopes the residual risk.

The JSON files were intentionally NOT modified (no `name:<…>` prefix
attempt, since n8n version compatibility is unknown). This is a defensible
process-only fix.

### B1 (delta spec v3): **STILL HOLDS**

Spot-checked the spec at line 5 (single-recipient encryption), line 39
(no public webhook), line 71-104 (single-recipient Requirement), line 234
(three-leg alerting). All v3 cuts preserved; no regression introduced by
the M2/M3 edits. No spec scenario was added or removed for M2/M3 (the spec
already describes whole-workflow outcomes, not n8n internal node wiring).

### B2 (rclone entrypoint shim): **STILL HOLDS**

`scripts/backup/runner/docker-entrypoint.sh` untouched in this session
(not in the diff for M2/M3). `Dockerfile.runner` unchanged at the
entrypoint chain — verified by grep of `docker-entrypoint.sh` across the
worktree.

### M1 (test-smoke cruft): **STILL HOLDS**

`scripts/backup/test-smoke.sh` not in the M2/M3 diff. No regression.

### N1 (heredoc camelCase): **STILL HOLDS**

`scripts/backup/README.md` and `openspec/changes/supabase-backup-policy/tasks.md`
drill heredocs were already aligned to camelCase in pass-2. M3 added task
8.4a (errorWorkflow re-link) which does not touch the drill heredoc at
task 10.6. No regression.

### N2 (spec mandate scope): **STILL HOLDS**

Spec line 230 still carries the M4-fix scope clarification ("status/*.json
+ success-response bodies use camelCase; error-response bodies retain
snake_case for legacy keys"). Not touched by M2/M3 edits.

### Q1 (preflight evidence): **STILL HOLDS**

`progress.md:13` preserves the documented preflight evidence from the
replant session. `progress.md:14-15` records pass-1 and pass-2 verdicts;
pass-3 will be appended after this review lands.

---

## New findings introduced by the fixes

None.

### Why no Watchdog/PreOp parallel fix was raised

The pass-3 brief asks whether the dropped-IF + dropped-`neverError`
pattern in Daily.json should be applied to Watchdog.json and PreOp.json
too. Verdict: NO — these are intentionally different by design.

- **Watchdog.json:** keeps `fullResponse: true, neverError: true` because
  its downstream `Check Staleness` Code node EXPLICITLY parses the
  response body (`$input.first().json.body || $input.first().json`) and
  generates structured alerts regardless of HTTP status. If
  `/status` returns 503 (`{"error":"r2_unreachable"}`), the Code node
  sees `body = {"error":"r2_unreachable"}`, finds `lastSuccess` is
  undefined, and dispatches the alert via the existing `Has Alerts?` →
  `Dispatch Alert` path. This is functionally equivalent error handling
  to "let the HTTP throw → errorWorkflow fires" — both produce an
  operator-visible alert; the Watchdog form preserves richer detail in
  the alert body. Removing `neverError: true` here would force ALL
  Watchdog non-2xx paths to fire the generic ErrorHandler instead of
  the more informative `Check Staleness`-derived alert. The current
  Watchdog wiring is correct; no fix needed.

- **PreOp.json:** is manual-trigger-only. The operator clicks "Execute
  Workflow" in the n8n UI and SEES the response in the execution log.
  `fullResponse: true, neverError: true` means the operator gets the
  full body (including the `error`, `local_sha256`, `r2_sha256`,
  `quarantined_to` keys per spec lines 64, 69, 215) regardless of HTTP
  status. The operator pulling on a manual trigger gets immediate
  visibility — there is no "silent failure" concern because there is
  no automated downstream consumer. The errorWorkflow binding STILL
  fires for `Validate Reason` Code node throws (which IS the spec's
  failure path for invalid regex per Scenarios at lines 41-49), because
  Code node exceptions are not suppressed by `neverError: true` on the
  HTTP node — they're a separate node's exception. The current PreOp
  wiring is therefore consistent with both the spec Scenarios AND the
  M2 design intent. No fix needed.

This asymmetry is intentional and design-justified. The pass-3 reviewer
considered raising it as a Minor (because Daily-vs-Watchdog-vs-PreOp
differ on neverError/fullResponse without inline justification in the
JSON), but the brief explicitly says "DO NOT raise findings against
deferred items already documented as follow-ups" and the operator's
explicit scope was M2 Daily-only. If a future contributor wants a
uniform pattern across all three workflows, that is a bounded
normalisation change, not a Phase 1 defect.

---

## Counts (NEW findings only — does NOT recount pass-1 or pass-2 findings)

- Blockers: 0
- Majors: 0
- Minors: 0
- Questions: 0

---

## What I looked at

- `scripts/backup/n8n/MyFinanceBackup-Daily.json` (full, 66 lines —
  primary subject of M2; validated via `node -e JSON.parse(...)`,
  confirmed node count, connection edges, errorWorkflow setting, and
  absence of neverError/fullResponse/IF node).
- `scripts/backup/n8n/MyFinanceBackup-Watchdog.json` (full, 173 lines —
  confirmed `neverError: true` is intentional given downstream Code
  node parses body; errorWorkflow setting at line 170 preserved).
- `scripts/backup/n8n/MyFinanceBackup-PreOp.json` (full, 128 lines —
  confirmed `neverError: true` is intentional for manual-trigger
  visibility; errorWorkflow at line 125 preserved; Code node throws
  for invalid regex still bypass neverError).
- `openspec/changes/supabase-backup-policy/tasks.md` (line 183-195 +
  drill heredoc at 210 — confirmed task 8.4a positioned correctly,
  covers all three errorWorkflow-bound workflows, includes verification
  step, N1 heredoc unaffected by M3 edit).
- `openspec/changes/supabase-backup-policy/specs/database-backups/spec.md`
  (lines 1-36 + 37-100 + 230-310 + grep for `Check HTTP Status`,
  `IF node`, `If node`, `non-2xx`, `errorWorkflow`, `ErrorHandler`,
  `Daily workflow`, `Daily.json`, `fullResponse` —
  confirmed no scenario references the dropped IF node, no orphan
  reference to `Check HTTP Status` introduced; the one `non-2xx`
  mention at line 271 is for Kuma fire-and-forget context, unrelated
  to M2).
- `openspec/changes/supabase-backup-policy/progress.md` (full, 40 lines
  — confirmed pass-2 verdict + N1/N2/M2/M3 fix notes present at lines
  15-32).
- `scripts/backup/README.md` (grepped for `Check HTTP Status`,
  `errorWorkflow`, `Error Workflow` — no matches, no operator-facing
  doc referencing the removed wiring).
- Cross-grep across the entire worktree for `Check HTTP Status`,
  `check-http-status` — only matches are in `progress.md:31` (correct
  past-tense framing) and `notes/adversarial-review-replant.md` (the
  pass-1 historical record, intentionally preserved).
- Cross-grep for `errorWorkflow` across `scripts/backup/n8n/` to
  enumerate the three files that need re-linking — verified task 8.4a's
  "Daily, PreOp, and Watchdog" enumeration matches exactly.

What I deliberately did NOT re-examine (per brief: do NOT re-litigate
prior findings beyond regression-check):
- B1 spec text beyond spot-checks at lines 5, 39, 71-104, 234.
- B2 entrypoint shim shell script (untouched in M2/M3 diff).
- M1 test-smoke.sh body (untouched in M2/M3 diff).
- N1 drill heredoc text (untouched in M2/M3 diff; pass-2 confirmed
  camelCase alignment).
- N2 spec mandate scope sentence at line 230 (untouched).
- Q-N1 broader camelCase sweep across `verifyResult` / `allPassed` etc
  (deferred per pass-2 + operator decision).
- m1-m6 minor findings from pass-1 (deferred per operator decision).
- Q2 Phase 2 design doc consistency (out of Phase 1 scope).
