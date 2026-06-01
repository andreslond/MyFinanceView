# progress.md — copied from openspec/templates/progress-template.md
# Schema v1. Rewritten by the backend-developer subagent after every closed
# task in tasks.md. Read by /opsx:apply at session start.

current_task: 1.1

last_completed: "proposal correction per adversarial-review-2026-05-31.md Path 2"

next_step: "branch feat/mvp-frontend-scaffold worktree creation + Vite scaffold (task 1.1)"

decisions_pending_design_update: []

resolved_findings:
  - "B1 (merchants table missing) → dropped capability myfinance-data-policies"
  - "B2 (category_confirmed column missing) → modal does not touch the column"
  - "B3 (V002 RLS collision) → dropped duplicate RLS migration; design.md D5 cites V002"
  - "B4 (SECURITY INVOKER blocks n8n path) → dropped trigger entirely"
  - "B5 (trigger semantics drift) → dropped trigger entirely"
  - "B6 (api-spec.yml empty) → acknowledged in proposal `## Forward-compat note` + design.md D4 caveat"
  - "B7 (missing Threat model) → added section to proposal.md"
  - "M1 (cycle window business logic) → 'transacciones más recientes' in proposal + spec"
  - "M3 (savings_goals RLS gap) → out of scope (no capability change)"
  - "M4 (raw_pattern algorithm undocumented) → moot (no trigger)"
  - "M5 (magic link verifiability) → split into 'request' + 'callback' scenarios"
  - "M6 (RLS test coverage) → moot (no RLS migration in this change)"
  - "M7 (DB mocking distinction) → moot (no DB tests in this change)"
  - "M8 (trigger/Java split-brain) → resolved by Gate-A decision: feedback loop owned by backend Java (TASK-BE-06)"
  - "M9 (stolen JWT) → documented in proposal ## Threat model"
  - "M10 (idempotency contradiction) → reconciled in design.md D8 + spec.md Category change modal"
  - "m1 (progress.md gate state) → resolved findings logged here"
  - "m2 (credentials in docs) → task 2.20 specifies variable names, not values"
  - "m4 (vague smoke test) → task 2.18 quantified"
  - "m5 (paginated total) → spec scenario clarifies total not exposed"
  - "m6 (category_confirmed legacy) → moot (column dropped from MVP)"
  - "m7 (supabase MCP precondition) → task 2.16 lists MCP precondition"
  - "m8 (Tailwind tokens) → AGENTS.md task 1.18 includes mapping"
  - "m9 (CI gate) → D7 build command chains typecheck+lint+test+build"
  - "m10 (trigger naming) → moot (no trigger)"
  - "Q1 (payment_day vs cut_day) → moot (no cycle window in MVP)"
  - "Q2 (UPSERT semantics) → moot (no trigger)"
  - "Q3 (sign-up flow) → resolved by D9 + spec scenario 'Sign Up no se muestra'"
  - "Q4 (categories user_id NULL) → reconciled in design.md D5 caveat: MVP only SELECT"
  - "Q5 (displayName fallback warning) → dropped per spec scenario 'Fallback'"

blockers: []

last_updated: "2026-06-01T05:30:00Z"
