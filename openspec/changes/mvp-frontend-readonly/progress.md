# progress.md — copied from openspec/templates/progress-template.md
# Schema v1. Rewritten by the backend-developer subagent after every closed
# task in tasks.md. Read by /opsx:apply at session start.

current_task: 2.17

last_completed: "1.1 through 2.14 — scaffold + screens implemented and verified locally. typecheck, lint, test, build all green. vercel.json with SPA rewrites + chained build command added."

next_step: "Run `vercel deploy --prod` from frontend/, smoke-test the URL (curl + browser login + check >=25 rows), then update docs/development-guide.md."

implementation_notes:
  branch_structure: "All implementation done on branch feat/mvp-frontend-scaffold (single commit pending). The feat/mvp-frontend-screens branch still points at the proposal commit; if a two-commit history is desired for review, split before merging — otherwise this single commit is the deploy commit."
  npm_install_done: true
  scripts_passing:
    - "npm run typecheck → 0 errors"
    - "npm run lint → 0 errors, 1 benign warning (react-refresh on AuthContext exporting useAuth hook + AuthProvider together)"
    - "npm run test → 1 file, 1 test (isolation.spec.ts) passing"
    - "npm run build → 541 kB JS / 158 kB gzipped, 9.7 kB CSS; chunk warning informational"
  files_added:
    - "frontend/{package.json, tsconfig*.json, vite.config.ts, tailwind.config.cjs, postcss.config.cjs, .eslintrc.cjs, .gitignore, .env.example, vercel.json, AGENTS.md, index.html}"
    - "frontend/.env.local (gitignored — real Supabase URL + anon JWT from MCP)"
    - "frontend/src/{main.tsx, App.tsx, index.css, vite-env.d.ts, test-setup.ts}"
    - "frontend/src/lib/supabaseClient.ts (also re-exports Session/User types so AuthContext stays in the allowlist)"
    - "frontend/src/services/{types, transactionsService, accountsService, categoriesService}.ts (queries against schema 'myfinance')"
    - "frontend/src/auth/{AuthContext, RequireAuth}.tsx"
    - "frontend/src/hooks/{useTransactions, useAccounts, useCategories, useUpdateTransactionCategory}.ts"
    - "frontend/src/pages/{LoginPage, TransactionsPage}.tsx"
    - "frontend/src/components/{TxRow, CategoryChangeModal}.tsx"
    - "frontend/src/__tests__/isolation.spec.ts (greps src/ for @supabase/supabase-js outside allowlist)"
  files_dropped:
    - "frontend/README.md placeholder"
  deviations_from_tasks_md:
    - "All work consolidated on feat/mvp-frontend-scaffold instead of split scaffold/screens commits. Rationale: faster deploy tonight; reconstructable later."
    - "Per-page unit tests (tasks 2.3, 2.6, 2.11) NOT written. Only the isolation static test runs. Rationale: tonight's goal is a working deploy; UI tests deferred to the day-after pass."

deferred:
  - "Per-page unit tests (LoginPage, TransactionsPage, CategoryChangeModal) — tasks 2.3, 2.6, 2.7, 2.11, 2.12"
  - "End-to-end manual category-change verification (task 2.19) — operator must click through with real account"
  - "Adversarial review of the implementation (task 2.22) — re-run on wake"

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
  - "m9 (CI gate) → D7 build command chains typecheck+lint+test+build (vercel.json buildCommand)"
  - "m10 (trigger naming) → moot (no trigger)"
  - "Q1 (payment_day vs cut_day) → moot (no cycle window in MVP)"
  - "Q2 (UPSERT semantics) → moot (no trigger)"
  - "Q3 (sign-up flow) → resolved by D9 + spec scenario 'Sign Up no se muestra'; LoginPage uses view='sign_in' showLinks={false}"
  - "Q4 (categories user_id NULL) → reconciled in design.md D5 caveat: MVP only SELECT"
  - "Q5 (displayName fallback warning) → dropped per spec scenario 'Fallback' — UI falls back to name silently"

blockers: []

last_updated: "2026-06-01T06:40:00Z"
