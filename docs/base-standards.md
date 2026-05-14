# Base Standards — MyFinanceView

> Single source of truth for cross-cutting principles. All other `docs/*.md` files (backend, frontend, data-model, …) extend and specialize this one. SPEC.md is the project north-star; this document is the *how*.

---

## 1. Source-of-Truth Hierarchy

Documents are not equal. When they conflict, the higher-priority one wins.

```
1. SPEC.md            ← project north-star (vision, stack, key decisions)
2. docs/*.md          ← detailed standards (this and siblings)
3. plans/*.md         ← per-feature plans
4. openspec/changes/  ← per-change artifacts (proposal, design, specs, tasks)
5. Notion             ← dynamic backlog (épicas, tareas, DoD)
6. archive/*          ← historical only, never authoritative
```

If you find guidance somewhere not listed above (e.g. CLAUDE.md hints, AGENTS.md), treat it as a pointer to one of the above, not as its own source.

## 2. Architectural Style

**Monolito modular por dominio.** Single Spring Boot deployable. Packages organized by bounded context (`domain/transaction`, `domain/savings`, …), not by technical layer. Each domain module owns its rules, repositories, and ports.

**Forbidden** (introducing any of these in the **application domain** — `domain/transaction`, `domain/savings`, `domain/billing`, etc. — requires explicit SPEC.md amendment):
- Clean Architecture *by layers* (no `domain/model`, `application/usecase`, `infrastructure/` split)
- Hexagonal ports & adapters maximalism
- CQRS, event sourcing, read/write split
- Microservices, multi-module Maven, internal libraries
- JPA/Hibernate (we use jOOQ — see [backend-standards.md](backend-standards.md))
- Reactive (WebFlux, Project Reactor) — we use Virtual Threads via Loom

**Inter-module communication:** plain Spring `@Service` calls inside the same context. No events, no message queues, no internal HTTP.

**Infrastructure carve-out (amendment 2026-05-13 — see SPEC.md §🏛️ "Excepción explícita"):** the microservices prohibition applies to the application domain only. *Infrastructure* services — n8n (Gmail ingest), `myfinance-backup-runner` (sidecar for encrypted snapshots), Traefik, Uptime Kuma — may live as separate processes under `scripts/<infra-domain>/` because they do not implement business rules and replacing them does not touch the Spring Boot deployable. Each new infra service requires an explicit `design.md` justification in the change that introduces it, plus a SPEC.md reference. The carve-out is narrow: it covers infrastructure that *supports* the application, not pieces of the application factored into separate processes. Originated from adversarial-review of [`supabase-backup-policy`](../openspec/changes/supabase-backup-policy/) (finding #2).

## 3. Development Methodology

The project applies three composable disciplines:

1. **Spec-Driven Development** — every endpoint or capability has a spec before it has code. The spec is the contract; tests validate against it.
2. **TDD** — RED (failing test) → GREEN (minimal code) → REFACTOR. No code without a test that justifies its existence.
3. **Tracer Bullets** — vertical slices through all layers (JWT → controller → service → jOOQ → DB → JSON) precede horizontal feature build-out. First make the wiring work end-to-end, then iterate.

### Workflow (per change)

```
1. /enrich-us              — refine the user story (skill)
2. /opsx:propose           — generate OpenSpec artifacts (proposal, design, specs, tasks)
3. /opsx:apply             — implement following the task checklist, TDD per task
4. adversarial-review      — red-team independent review (subagent or skill)
5. /commit                 — focused commit + PR
6. /opsx:archive           — close the change
7. /openspec-sync-specs    — merge delta specs into canonical specs
8. /update-docs            — sync SPEC.md, docs/, Notion to reflect what landed
```

Skipping a step requires justification in the commit body.

## 4. Code Quality Bar

### Money
- `BigDecimal` always. `double`/`float` for money is a **bug**.
- `RoundingMode.HALF_EVEN`, scale 2 unless documented otherwise.
- Money values are immutable; never mutate, always return new instances.
- Currency must be explicit (`Currency` type or ISO 4217 string field).

### Time
- Persist `OffsetDateTime` in UTC. Use `LocalDate` for calendar dates (e.g. cut day).
- Convert to `America/Bogota` only in the presentation layer.
- Never store local times in the DB.

### Identifiers
- `UUID` for all primary keys. No `Long`, no `String`.
- IDs are generated server-side (`uuid_generate_v4()` in Postgres) — clients do not submit IDs.

### Errors
- HTTP errors follow RFC 7807 (`ProblemDetail`, native in Spring 6+).
- Never expose stack traces to clients.
- Never log tokens, passwords, raw card numbers, or personal data.

### DTOs
- Records, not classes. Immutable by construction.
- Records live in `api/dto/`. Domain types live in `domain/{module}/`.

## 5. Testing Standards

| Test type | Tool | When |
|---|---|---|
| Unit | JUnit 5 + AssertJ | Pure logic (calculators, mappers). Many. Fast. |
| Contract | REST-assured | Per endpoint, validates HTTP shape (status, JSON keys, types). |
| Integration | Testcontainers (Postgres 17) | Per use case, uses real `myfinance` schema. |
| End-to-end | Manual smoke / `/show-spec-working` skill | Per change, before archive. |

**No mocking the database.** Project rule: integration tests use Testcontainers with the same schema as production. Mocked-DB tests pass with broken migrations — that's a class of bug we refuse.

**Naming:** `should{Result}When{Condition}` — e.g. `shouldReturnTransactionsSortedByConfidenceWhenFetchingPendingReview`.

## 6. Documentation Discipline

Three rules:

1. **Edit existing before creating new.** A new markdown file is a debt; pay it only when adding fundamentally new content.
2. **Comments only for WHY.** Code names already say what. Comments cite non-obvious constraints, workarounds, or invariants.
3. **Docs decay.** After every archive, `/update-docs` skill must run to sync SPEC.md, docs/, and Notion.

See [documentation-standards.md](documentation-standards.md) for the full hierarchy and write-back rules.

## 7. Security Baseline

- Supabase JWT validated by Spring Security on every request. No anonymous endpoints (except internal webhooks behind shared secrets).
- RLS is bypassed by the backend service-role connection — **authz lives in the application layer**, scoped by the `user_id` extracted from the validated JWT.
- Never trust a `user_id` from the request body. Always derive from the JWT.
- Secrets via env vars / Supabase Vault. Never in repo.
- See [backend-standards.md §Security](backend-standards.md).

## 8. AI-Assisted Development Standards

This project actively uses AI coding assistants (Claude Code primarily, others via standard configs). To keep collaboration coherent:

- **Skills** ([~/.claude/skills/](file:///C:/Users/Latmin/.claude/skills/)) are reusable workflow prompts. New skills follow [writing-skills](file:///C:/Users/Latmin/.claude/skills/writing-skills/SKILL.md) (Iron Law: NO SKILL WITHOUT A FAILING TEST FIRST).
- **Agents** (`.claude/agents/`) are isolated subagent definitions with project context.
- **Memory** lives in `~/.claude/projects/{slug}/memory/` and persists decisions across sessions.
- AI-generated commits include the `Co-Authored-By: Claude Opus 4.7 (1M context)` line.

## 9. Definition of "Done"

A change is done when **all** of the following are true:

- [ ] Spec exists in `openspec/specs/` (or amended) and acceptance criteria are listed.
- [ ] Code is implemented and follows §2 (architecture) and §4 (quality).
- [ ] Tests at the appropriate level are green (§5).
- [ ] An adversarial review has been performed (skill or subagent), all Blockers resolved.
- [ ] `/update-docs` has run; SPEC.md/Notion reflect reality.
- [ ] Commit is focused, in English, imperative, with co-author line.
- [ ] OpenSpec change is archived.
