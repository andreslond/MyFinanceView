# Frontend Standards — MyFinanceView

> **Status:** placeholder. The frontend is not yet started. This document will be filled when TASK-UI-* tasks become active. Until then, see [SPEC.md §11](../SPEC.md) for the high-level vision.

---

## 1. Scope (planned)

- **Stack:** React 19 + Vite + TypeScript (decision pending; current preference).
- **State:** React Query for server state, Zustand for UI state.
- **Auth:** Supabase JS client for login/refresh; JWT forwarded to backend on every call.
- **Routing:** React Router.
- **UI design:** Mockups produced separately in Claude Design; this doc covers code conventions, not visual design.

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
- Backlog: Notion épica "Épica 3 — App / UI".
- Backend contract: [backend-standards.md](backend-standards.md) and the eventual `docs/api-spec.yml`.

---

*Until this doc is filled, do not generate frontend code for this project beyond minimal demo scripts.*
