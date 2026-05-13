# CLAUDE.md — MyFinanceView

> Entrypoint for Claude Code in this repository. Read this first; it tells you where everything authoritative lives.

---

## What this project is

**MyFinanceView** is a personal finance backend for one user in Bogotá, Colombia. It ingests bank transaction emails via Gmail + n8n, categorizes them with an LLM (with a feedback loop), and exposes a REST API for a future React UI. Backend: **Java 25 + Spring Boot 3.4+ + jOOQ + Supabase Postgres**.

See [SPEC.md](SPEC.md) for the full vision.

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
3. **[plans/](plans/)** — per-feature plans (current: `savings-goals-plan.md`).
4. **[openspec/](openspec/)** — per-change artifacts:
   - `changes/<id>/` — active proposals (proposal.md, design.md, specs/, tasks.md)
   - `specs/<capability>/` — canonical capability specs
   - `archive/` — completed changes
5. **[Notion page](https://www.notion.so/35d8c9b709f081c08d62f7257ce3db57)** — dynamic backlog (épicas, tareas, DoD).
6. **[archive/](archive/)** — obsolete docs preserved for history. **Never authoritative.**

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

## Workflow (per change)

The full spec-driven + TDD + adversarial-review flow:

```
1. /enrich-us       — refine the user story from Notion or text
2. /opsx:propose    — generate OpenSpec artifacts
3. /opsx:apply      — implement, TDD per task
4. /adversarial-review (skill) or Agent(adversarial-reviewer) — red-team check before merge
5. /commit          — focused commit + PR
6. /opsx:archive    — close the change
7. /openspec-sync-specs — merge deltas into canonical specs
8. /update-docs     — sync SPEC.md / docs/ / Notion
```

See [base-standards.md §3](docs/base-standards.md) for details.

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
- `openspec-sync-specs` — merge delta specs into canonical capability specs.
- `show-spec-working` — live demo of a spec via curl.
- OpenSpec built-ins: `openspec-propose`, `openspec-apply-change`, `openspec-archive-change`, `openspec-explore`.

### Agents (project-level — [`.claude/agents/`](.claude/agents/))
- `backend-developer` — Java/Spring/jOOQ implementer with project conventions baked in.
- `frontend-developer` — React placeholder (activate when frontend starts).
- `product-strategy-analyst` — for ideation/value-prop refinement.
- `adversarial-reviewer` — subagent version of the skill, for isolated red-team reads.
- `code-auditor` — subagent version of the skill, for milestone audits.

## Conventions for AI-assisted work

- AI commits include `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- Memory lives in `~/.claude/projects/c--dev-workspace-MyFinanceView/memory/`.
- When asked to "act as backend-developer" → spawn the subagent, don't roleplay in main context.
- When creating or editing a skill → enforce [writing-skills](file:///C:/Users/Latmin/.claude/skills/writing-skills/SKILL.md) Iron Law: pressure-test before deployment.

## Don'ts

- Don't cite `archive/agents.md` or `archive/database-model-plan.md` as guidance — they are obsolete.
- Don't introduce new top-level docs without justification. Prefer editing `docs/*.md`.
- Don't skip `/enrich-us` for fuzzy tickets. Vague story → bad spec → wasted implementation.
- Don't run `/commit` proactively. It's always explicit.
- Don't downgrade tests to mocks "for speed." Project rule: real DB for integration.
- Don't introduce frontend code beyond demos until [frontend-standards.md](docs/frontend-standards.md) is filled.
