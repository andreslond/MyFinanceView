# Frontend Standards — MyFinanceView

> **Status:** placeholder for code conventions. The frontend is not yet started. This document will be filled when TASK-UI-* tasks become active. The **visual language is already defined** — see [design/](design/). Until then, see [SPEC.md §11](../SPEC.md) for the high-level vision.

---

## 1. Scope (planned)

- **Stack:** React 19 + Vite + TypeScript (decision pending; current preference).
- **State:** React Query for server state, Zustand for UI state.
- **Auth:** Supabase JS client for login/refresh; JWT forwarded to backend on every call.
- **Routing:** React Router.
- **UI design:** Canonical visual language lives in [design/design-system.md](design/design-system.md); screens, flows, and dev handoff in [design/ui-handoff.md](design/ui-handoff.md). Original bundle preserved immutably under [design/raw/](design/raw/). This doc covers code conventions only.

## 2. Principles (carry over from base-standards)

- The frontend **never** computes monetary aggregations. Always render values the backend already computed and formatted.
- The frontend **never** validates business invariants. Validation happens server-side; the UI shows server errors.
- Pagination, filtering, sorting are server-driven (URL query params reflect the request).
- All numeric/date values from the backend are strings or ISO; the frontend parses them with explicit formatters.

## 3. Decisions Pending

- TypeScript strictness profile.
- Component library (none / Radix / shadcn / custom).
- Forms library (react-hook-form is the likely pick).
- Test stack (Vitest + Testing Library expected).
- Deployment target (Vercel is the working assumption).

## 4. References

- [base-standards.md](base-standards.md) for cross-cutting principles.
- [design/design-system.md](design/design-system.md) — canonical tokens, type, colors, components.
- [design/ui-handoff.md](design/ui-handoff.md) — screens, flows, interactions.
- [design/raw/](design/raw/) — original design bundle, immutable.
- Backlog: Notion épica "Épica 3 — App / UI".
- Backend contract: [backend-standards.md](backend-standards.md) and [api-spec.yml](api-spec.yml).

---

*Until this doc is filled, do not generate frontend code for this project beyond minimal demo scripts.*
