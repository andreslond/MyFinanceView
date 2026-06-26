# frontend/AGENTS.md — operative rules for any agent touching this folder

> If you are an AI agent editing files under `frontend/`, read this first. The rules below are hard guardrails. The architectural rationale lives in `../archive/openspec-legacy/changes/archive/2026-06-01-mvp-frontend-readonly/design.md`.

---

## Hard rules (CI enforces these)

### 1. `@supabase/supabase-js` is isolated to two locations

You may import `@supabase/supabase-js` ONLY from:

- `src/lib/supabaseClient.ts` (the single client instance)
- `src/services/**/*.ts` (the data-access layer)

Anywhere else (`src/components/**`, `src/pages/**`, `src/hooks/**`, `src/auth/**` except `AuthContext.tsx` which uses `supabase` via `lib/`) is blocked by:

- ESLint `no-restricted-imports` in `.eslintrc.cjs`
- A static Vitest test at `src/__tests__/isolation.spec.ts` (greps the source tree)

**Why:** when the backend Java REST API aterriza, the migration consists of swapping the body of each `service.ts` from `supabase.from(...).select(...)` to `fetch('/api/v1/...')` with the same JWT. Components, hooks, routing, state — nada cambia. If you put `supabase-js` in a page, the migration becomes a refactor.

### 2. Frontend NEVER aggregates money

No `.reduce()` on amounts. No "total de la página", no "total filtrado", no client-side averages.

If product asks for a total, the path is:
1. Create a SQL view in Supabase (`myfinance.v_<thing>_total`) that returns one row with the aggregate.
2. Add a method in the relevant service (`transactionsService.totalForFilters(...)`).
3. The component renders the value.

Per `../docs/base-standards.md §2`: "frontend nunca computa agregaciones de plata ni valida invariantes de negocio."

### 3. The modal does NOT touch `merchants` and does NOT touch `category_confirmed`

The category-change modal emits ONLY:

```sql
UPDATE myfinance.transactions SET category_id = $1 WHERE id = $2;
```

It does NOT:
- Update `myfinance.merchants` (no `confidence` increment, no UPSERT). That's TASK-BE-06 of the backend Java.
- Touch `transactions.category_confirmed`. That column does not exist in the current schema; introducing it is pending V008.

**Why:** the original proposal (pre-Path 2) had a Postgres trigger that did the feedback loop. The adversarial review of 2026-06-01 (`../archive/openspec-legacy/changes/archive/2026-06-01-mvp-frontend-readonly/adversarial-review-2026-05-31.md`) found 7 Blockers around that trigger (table/columns missing, RLS collisions, SECURITY INVOKER blocking n8n's `service_role`, split-brain with the future Java backend). The operator chose Path 2: ship without the trigger. The cost is no UI-driven learning until backend Java; the benefit is shipping tonight.

---

## Folder structure (don't reshape without updating this file)

```
frontend/
├── index.html
├── package.json, tsconfig*.json, vite.config.ts, vitest.config.ts (in vite.config),
│   tailwind.config.cjs, postcss.config.cjs, .eslintrc.cjs
├── .env.example          ← committed (no values)
├── .env.local            ← gitignored (real values)
├── AGENTS.md             ← this file
└── src/
    ├── main.tsx          ← React Query + Router + AuthProvider bootstrap
    ├── App.tsx           ← route table
    ├── index.css         ← Tailwind layers + Geist font helpers
    ├── vite-env.d.ts     ← env var types
    ├── test-setup.ts     ← @testing-library/jest-dom matchers
    ├── lib/
    │   └── supabaseClient.ts   ← ONLY place createClient() runs
    ├── services/
    │   ├── types.ts            ← DTO shapes (camelCase, ISO strings, BigDecimal-as-string)
    │   ├── transactionsService.ts
    │   ├── accountsService.ts
    │   └── categoriesService.ts
    ├── auth/
    │   ├── AuthContext.tsx     ← useAuth() / session state
    │   └── RequireAuth.tsx     ← route guard
    ├── hooks/
    │   ├── useTransactions.ts
    │   ├── useAccounts.ts
    │   ├── useCategories.ts
    │   └── useUpdateTransactionCategory.ts
    ├── pages/                  ← LoginPage, TransactionsPage (branch feat/mvp-frontend-screens)
    ├── components/             ← CategoryChangeModal, TxRow, etc. (branch feat/mvp-frontend-screens)
    └── __tests__/
        └── isolation.spec.ts   ← enforces rule 1
```

---

## DTO shape (inventado — pending api-spec.yml alignment)

The shapes in `src/services/types.ts` are **invented** by this MVP. When `docs/api-spec.yml` ships real schemas for `TransactionDTO`, `AccountDTO`, `CategoryDTO` (post-TASK-BE-04), realign:

- Field naming: camelCase (already done).
- Time: ISO 8601 strings (`occurredAt: string`), parsed by `Date(...)` only at the render boundary.
- Money: **string** (`amount: string`). Render with `Decimal.js` or hand-format. Never `Number(amount)` for math.
- IDs: `string` (UUIDs).
- Pagination: `Page<T>` with `hasMore: boolean` — no total exposed (avoids `count=exact` overhead and matches the spec `Paginación con siguiente/anterior`).

When refactoring against the real api-spec.yml, the change is localized to:
- `src/services/types.ts` (DTO definitions)
- The `rowToDTO` mapper in each service
- Possibly the query construction if the REST endpoint paginates differently

The rest of the app (`hooks/`, `pages/`, `components/`) is contract-stable.

---

## Tailwind tokens ↔ design-system.md mapping

Per `../docs/design/design-system.md §4`. Available Tailwind utilities:

| Tailwind class | Token | Hex |
|---|---|---|
| `bg-surface-base` | `--bg` | `#0B0B0F` (dark primary) |
| `bg-surface-raised` | `--surface-raised` | `#15151C` |
| `bg-surface-sunken` | `--surface-sunken` | `#08080C` |
| `border-surface-border` | `--border` | `rgba(255,255,255,0.07)` |
| `text-content-primary` | text high | `#F5F5F7` |
| `text-content-secondary` | text mid | `#9CA3AF` |
| `text-content-muted` | text low | `#6B7280` |
| `text-brand-purple` / `bg-brand-purple` | `--c-purple` | `#7C5CFF` (primary accent / CTA) |
| `text-brand-cyan` | `--c-cyan` | `#22D3EE` (food, email-synced) |
| `text-brand-positive` | `--c-positive` | `#34E0A1` (income, on-pace) |
| `text-brand-negative` | `--c-negative` | `#FF6B6B` (overspend) |
| `text-brand-amber` | `--c-amber` | `#F4B86A` (transport) |
| `text-brand-coral` | `--c-coral` | `#FF9EA0` (shopping) |
| `text-brand-slate` | `--c-slate` | `#8B95B2` (bills) |
| `text-brand-green` | `--c-green` | `#6FE39A` (entertainment) |
| `rounded-chip` (7px) / `rounded-glyph` (11) / `rounded-btn` (12) / `rounded-card` (22) / `rounded-sheet` (26) | radii §5 |
| `font-sans` (Geist) / `font-mono` (Geist Mono) | type §3 |

**Category color mapping is canonical — never swap.** Use the matching class name when rendering category glyphs/badges.

---

## Environment

Build-time vars (Vite reads from `.env`, `.env.local`):

- `VITE_SUPABASE_URL` — Supabase project URL.
- `VITE_SUPABASE_ANON_KEY` — Supabase legacy JWT anon key (compatible with `@supabase/auth-ui-react`).

Copy `.env.example` to `.env.local` and fill from `mcp__claude_ai_Supabase__get_project_url` + `get_publishable_keys`. Never commit `.env.local`.

---

## Common scripts

```
npm install        # once after pulling deps
npm run dev        # vite dev server, http://localhost:5173
npm run typecheck  # tsc -b --noEmit (strict mode)
npm run lint       # eslint .ts/.tsx
npm run test       # vitest run (includes isolation.spec)
npm run build      # tsc -b && vite build → dist/
npm run preview    # serve dist/ locally
```

The Vercel build command MUST chain `typecheck && lint && test && build` so CI is a single gate (design.md D7).

---

## When you would normally reach for…

- **A new top-level page** → add the route in `App.tsx`, the page in `pages/`, the service method if needed in `services/`. Don't introduce a new directory naming convention.
- **A reusable card** → `components/`. Keep it presentational. Don't pull `supabase-js`; consume hooks.
- **A new entity (e.g. budgets)** → add `services/budgetsService.ts`, mirror the existing pattern (row interface, `rowToDTO`, list method).
- **A debug log of money** → don't. Render the formatted string. If you need to inspect, use the Network tab.
- **A backend endpoint reachable from the frontend** → this is the migration path. Add a service method that fetches against `fetch('/api/v1/...')` and pass the JWT from `useAuth()`. Don't shim around `supabase-js` for that — replace it.

---

## Related reading

- `../archive/openspec-legacy/changes/archive/2026-06-01-mvp-frontend-readonly/proposal.md` — Why this MVP, Threat model.
- `../archive/openspec-legacy/changes/archive/2026-06-01-mvp-frontend-readonly/design.md` — D1–D9 decisions, risks.
- `../archive/openspec-legacy/changes/archive/2026-06-01-mvp-frontend-readonly/specs/frontend-mvp/spec.md` — Scenarios.
- `../archive/openspec-legacy/changes/archive/2026-06-01-mvp-frontend-readonly/adversarial-review-2026-05-31.md` — What we corrected.
- `../docs/base-standards.md`, `../docs/frontend-standards.md`, `../docs/design/design-system.md`.
