# CLAUDE.md — MyFinanceView

> Entrypoint for Claude Code in this repository. Read this first; it tells you where everything authoritative lives.

---

## What this project is

**MyFinanceView** is a personal finance backend for one user in Bogotá, Colombia. It ingests bank transaction emails via Gmail + n8n, categorizes them with an LLM (with a feedback loop), and exposes a REST API for a future React UI. Backend: **Java 25 + Spring Boot 3.4+ + jOOQ + Supabase Postgres**.

See [SPEC.md](SPEC.md) for the full vision.

## Repository layout (monorepo)

```
myfinance-view/
├── backend/      ← Java/Spring Boot — pom.xml, mvnw, src/, database/
├── frontend/     ← Placeholder for React 19 + Vite + TS (Épica 3, not yet started)
├── docker/       ← docker-compose.yml (mounts ../backend/database/*)
├── docs/         ← Cross-cutting standards + api-spec.yml + design/ + uncle-bob/ (harness)
├── plans/        ← Per-feature plans (e.g. savings-goals-plan.md)
├── features/     ← Gherkin contracts (.feature) — harness Uncle Bob
├── progress/     ← Per-feature session tracking — harness Uncle Bob
├── scripts/      ← harness/ helpers + cross-cutting helpers
├── archive/      ← Obsolete docs incl. openspec-legacy/ (never authoritative)
├── .claude/      ← Agents, skills, commands, worktrees
├── .github/      ← CI (working-directory: backend)
└── SPEC.md, CLAUDE.md, AGENTS.md, CHECKPOINTS.md, feature_list.json, init.ps1
```

All Maven commands run from `backend/`. Canonical structure: [SPEC.md §3.1](SPEC.md). Rationale for the move: [plans/2026-05-31-monorepo-restructure-design.md](plans/2026-05-31-monorepo-restructure-design.md).

## Source-of-truth hierarchy (read in this order when in doubt)

1. **[SPEC.md](SPEC.md)** — project north star: vision, stack, key decisions.
2. **[docs/](docs/)** — detailed standards:
   - [base-standards.md](docs/base-standards.md) — cross-cutting principles, workflow, DoD
   - [backend-standards.md](docs/backend-standards.md) — Java/Spring/jOOQ specifics
   - [data-model.md](docs/data-model.md) — current `myfinance` schema + pending migrations
   - [development-guide.md](docs/development-guide.md) — local setup, commands, troubleshooting
   - [documentation-standards.md](docs/documentation-standards.md) — what doc goes where
   - [frontend-standards.md](docs/frontend-standards.md) — placeholder for React
   - [api-spec.yml](docs/api-spec.yml) — OpenAPI contract (canonical)
3. **[plans/](plans/)** — per-feature plans and design notes (e.g. `savings-goals-plan.md`, `2026-05-31-monorepo-restructure-design.md`).
4. **[AGENTS.md](AGENTS.md) + [docs/uncle-bob/](docs/uncle-bob/)** — the **Uncle Bob harness** (current spec-driven flow for pure domain work): pipeline, agents, conventions. `feature_list.json` is the domain backlog; `project-spec.md`/`features/`/`progress/` are its live artifacts.
5. **[Notion page](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57)** — dynamic backlog (épicas, tareas, DoD).
6. **[archive/](archive/)** — obsolete docs preserved for history (incl. `archive/openspec-legacy/` — the retired OpenSpec harness + its 5 canonical specs). **Never authoritative.**

If two sources disagree, the higher-priority one wins. See [documentation-standards.md §2](docs/documentation-standards.md).

## Architectural style — non-negotiable

**Monolito modular por dominio.** One Spring Boot deployable. Packages by bounded context (`domain/transaction`, `domain/savings`, …), not by technical layer.

**Reject** any of these without an explicit SPEC.md amendment:
- Clean Architecture *by layers* (`domain/model`, `application/usecase`, `infrastructure/`)
- Hexagonal ports & adapters maximalism
- CQRS, event sourcing, microservices, multi-module Maven
- JPA / Hibernate (use jOOQ)
- WebFlux / Reactor (use Virtual Threads via Loom)

See [base-standards.md §2](docs/base-standards.md) and [backend-standards.md §2](docs/backend-standards.md).

## Workflow — the Uncle Bob harness

The project's spec-driven flow is the **Uncle Bob harness** (migrated from signSystem on 2026-06-25; it replaced the OpenSpec `/opsx:*` flow, now archived under `archive/openspec-legacy/`). It applies a **conversación → Gherkin → TDD → review → mutación** pipeline, scoped to the **pure domain** `backend/src/main/java/com/myfinanceview/domain/**`.

Read **[AGENTS.md](AGENTS.md)** first (the navigation map), then **[docs/uncle-bob/workflow.md](docs/uncle-bob/workflow.md)**.

**Pipeline (one feature at a time, one human gate on the `.feature`):**

```
pending
  → spec_partner    — conversación → project-spec.md
  → gherkin_author  — → features/<name>.feature
  → ⏸ HUMANO APRUEBA los escenarios
  → in_progress
  → tdd_craftsman   — Rojo → Verde → Refactor (un test a la vez)
  → judge           — el review es el juego entero
  → mutation_tester — PIT, 100% sobre líneas nuevas
  → done
```

- **Orchestration:** act as the `craftsman_lead` (Opus) for domain feature work; launch the 5 subagents via the `Agent` tool with `model: "sonnet"`. The lead never writes code. See `.claude/agents/craftsman_lead.md`.
- **Backlog:** `feature_list.json` (statuses `pending → spec_ready → in_progress → done`). Live artifacts: `project-spec.md`, `features/<name>.feature`, `progress/{current,history}.md` + per-feature `tdd_/judge_/mutation_` logs.
- **Verification before `done`:** `init.ps1` green, domain tests green (`mvnd`), PIT 100% on new lines. See `docs/uncle-bob/verification.md`.
- **Scope guard:** anything outside the pure domain (REST controllers, jOOQ repos, SQL migrations, auth, frontend, infra) does **not** go through this pipeline — work it directly or via `backend-developer`. See AGENTS.md §0.
- **Optional hooks:** `.claude/settings.harness.example.json` ships an opt-in PostToolUse (path-scoped to `domain/**`) + Stop (`init.ps1`) hook set. Not active by default — the operator merges it into `.claude/settings.json` if wanted. (The project previously rolled back an intrusive SessionStart hook after dogfooding; these are kept cheap, scoped, and vetoable.)
- `enrich-us` (skill) can still refine a fuzzy story before the `spec_partner` conversation. `commit` / `update-docs` close out as before.

## Code quality bar (highlights)

- Money: `BigDecimal` always, `HALF_EVEN`, scale 2. `double`/`float` is a bug.
- Time: `OffsetDateTime` UTC in DB; convert to `America/Bogota` only in presentation.
- IDs: `UUID` always.
- DTOs: Records, immutable.
- Errors: RFC 7807 `ProblemDetail`.
- Tests: Testcontainers for integration. **No mocking the database.**
- Naming: `should{Result}When{Condition}` for tests.

Full rules: [base-standards.md §4–5](docs/base-standards.md), [backend-standards.md §10](docs/backend-standards.md).

## Skills & agents in this project

### Skills (user-level — `~/.claude/skills/`)
- `enrich-us` — refine vague user stories (Notion-aware).
- `adversarial-review` — red-team review before merge.
- `code-auditing` — milestone breadth audit.
- `update-docs` — sync docs after a change lands.
- `commit` — focused commit + PR.
- `writing-skills` — Iron Law for creating new skills (TDD).
- `show-spec-working` — live demo of a spec via curl.

> The OpenSpec skills/commands (`openspec-*`, `/opsx:*`, `openspec-sync-specs`) and `scripts/preflight.ps1` were **removed** in the 2026-06-25 harness migration. The current flow is the Uncle Bob harness (see "Workflow" above).

### Agents (project-level — [`.claude/agents/`](.claude/agents/))
**Uncle Bob harness pipeline:**
- `craftsman_lead` — orchestrator (Opus); coordinates the 5 phases, never writes code.
- `spec_partner` — conversational spec partner → `project-spec.md` (Sonnet).
- `gherkin_author` — distils `features/<name>.feature` (Sonnet).
- `tdd_craftsman` — strict TDD of one approved feature (Sonnet).
- `judge` — review/approve-or-reject (Sonnet).
- `mutation_tester` — PIT mutation gate (Sonnet).

**General:**
- `backend-developer` — Java/Spring/jOOQ implementer for non-domain work (controllers, repos, migrations).
- `frontend-developer` — React placeholder (activate when frontend starts).
- `product-strategy-analyst` — for ideation/value-prop refinement.
- `adversarial-reviewer` — subagent version of the skill, for isolated red-team reads.
- `code-auditor` — subagent version of the skill, for milestone audits.

## Conventions for AI-assisted work

- AI commits include `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- Memory lives in `~/.claude/projects/D--dev-workspace-MyFinanceView/memory/`.
- When asked to "act as backend-developer" → spawn the subagent, don't roleplay in main context.
- When creating or editing a skill → enforce [writing-skills](file:///C:/Users/Latmin/.claude/skills/writing-skills/SKILL.md) Iron Law: pressure-test before deployment.

## Don'ts

- Don't cite `archive/` (incl. `archive/openspec-legacy/`) as guidance — it's preserved for history, never authoritative.
- Don't use the retired OpenSpec flow: there is no `/opsx:*`, no `openspec-*` skill, no `scripts/preflight.ps1`. Use the Uncle Bob harness.
- Don't introduce new top-level docs without justification. Prefer editing `docs/*.md`.
- Don't skip the spec conversation (`spec_partner`, optionally `enrich-us` first) for fuzzy domain features. Vague story → bad contract → wasted implementation.
- Don't run the domain pipeline for non-domain work (controllers, repos, migrations, frontend, infra) — it's scoped to `domain/**` only.
- Don't declare a feature `done` without `judge` APPROVED **and** PIT 100% on new lines.
- Don't launch the 5 harness subagents on Opus — Sonnet always; only `craftsman_lead` is Opus.
- Don't run `commit` proactively. It's always explicit.
- Don't downgrade tests to mocks "for speed." Project rule: real DB for integration; pure-domain tests need no DB at all.
- Don't introduce frontend code beyond demos until [frontend-standards.md](docs/frontend-standards.md) is filled.
