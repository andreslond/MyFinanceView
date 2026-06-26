current_task: none

last_completed: "2.14 — scaffold + screens + Vercel deploy + spec docs landed in main via direct merge of feat/mvp-frontend-scaffold on 2026-06-01. Closed with 403 on /rest/v1/{transactions,accounts,categories} as documented terminal state."

next_step: "archive this change to openspec/changes/archive/2026-06-01-mvp-frontend-readonly/ and start backend-mvp-readonly"

decisions_pending_design_update: []

implementation_notes:
  status: "CLOSED with 403"
  closure_reason: "Operator rejected retroactively the schema-exposure path D1 depended on. Frontend at Vercel responds 403 on all myfinance.* queries. Intentional gating, not a bug. See notes/closing.md."
  superseded_by: "backend-mvp-readonly — replaces D1 (Frontend direct to Supabase) with backend Java REST + service_role + WHERE user_id"
  remaining_unfinished_tasks:
    - "2.3, 2.6, 2.7, 2.11, 2.12 — per-page unit tests (only isolation.spec runs in CI)"
    - "2.19 — manual end-to-end category-change verification"
    - "2.22 — adversarial review of implementation"
    - "2.20 — docs/development-guide.md validation"
    - "3.1 — /opsx:archive — done manually in this commit"
    - "3.2 — /openspec-sync-specs — done manually (canonical at openspec/specs/frontend-mvp/spec.md)"
    - "3.3 — /update-docs — deferred until backend-mvp-readonly lands"

blockers: []

last_updated: "2026-06-01T19:00:00Z"
