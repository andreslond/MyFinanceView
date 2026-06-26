---
created: 2026-06-26T04:10:00Z
updated: 2026-06-26T04:25:00Z
branch: chore/uncle-bob-harness-migration
worktree: .claude/worktrees/uncle-bob-harness-migration
mode: paused
---

# Migración de harness: OpenSpec → Uncle Bob (Java)

## Next step
Revisar/mergear el **PR #4** (https://github.com/andreslond/MyFinanceView/pull/4). Antes de confiar en PIT: correr el codegen jOOQ (`mvnd -P codegen generate-sources` con Docker/DB) y verificar en JDK 25 que `mvnd org.pitest:pitest-maven:mutationCoverage` arranca (versiones pitest-maven 1.17.4 + pitest-junit5-plugin 1.2.1).

## Goal
Reemplazar el harness OpenSpec (`/opsx:*`) por el harness Uncle Bob (conversación → Gherkin → TDD → review → mutación PIT), portado de signSystem y adaptado a Java/Maven/JUnit/PIT, scopeado al dominio puro `backend/src/main/java/com/myfinanceview/domain/**`. Actualizar Engram. Avisar cuando esté listo.

## Done this session
- Harness completo: 6 agentes (`.claude/agents/{craftsman_lead,spec_partner,gherkin_author,tdd_craftsman,judge,mutation_tester}.md`), 7 docs `docs/uncle-bob/*.md`, `AGENTS.md`, `CHECKPOINTS.md`, `feature_list.json` (4 features de dominio, todas `pending`), `project-spec.md`, `init.ps1`, `features/.gitkeep`, `progress/{current,history}.md`.
- PIT añadido a `backend/pom.xml` (`pitest-maven`, targetClasses `com.myfinanceview.domain.*`, STRONGER, threshold 100).
- OpenSpec retirado: borrados 4 comandos `opsx/`, 4 skills `openspec-*`, `scripts/preflight.ps1`, `docs/workflow-cheatsheet.html`; `openspec/` → `archive/openspec-legacy/` (55 renames) con README; `docs/workflow.md` → stub redirect.
- Sweep de referencias: 29 archivos repunteados al harness o a `archive/openspec-legacy/`. Grep final: 0 refs vivas a openspec fuera de `plans/` y `.handoffs/`.
- `init.ps1` corre **verde (exit 0)**. Engram id 62 guardado + 2 juicios `related`. Memoria local: `project_uncle_bob_harness_migration.md` + índice.
- **Commiteado y pusheado:** commit `949ad93` (migración, 118 archivos) en `chore/uncle-bob-harness-migration`, pusheado a origin, **PR #4 abierto** contra `main`. Hooks NO activados (por decisión del operador).

## Working tree state
- **Committed (this branch):** `949ad93 chore(harness): replace OpenSpec with the Uncle Bob harness (Java port)` + el commit de este handoff. Pusheados a `origin/chore/uncle-bob-harness-migration`.
- **Staged / Unstaged:** ninguno tras commitear.
- **Build:** NO verificado en verde — checkout frío, codegen jOOQ sin correr (`mvnd compile` falla con `jooq.generated does not exist` hasta correr codegen). Entorno, no regresión.

## Pending
- Revisar/mergear PR #4. `main` sigue limpio (la migración vive en la rama).
- **Hooks opt-in, sin activar:** `.claude/settings.harness.example.json` + `scripts/harness/post-edit-domain-test.ps1`. Si en el futuro se quieren, fusionar el ejemplo en `.claude/settings.json` (requiere autorización explícita; el clasificador bloquea la auto-escritura).
- Arrancar la primera feature de dominio (`billing_period_resolution`) con el `craftsman_lead` → `spec_partner`; refinar `feature_list.json` al conversarla.
- Verificar versiones de PIT contra JDK 25 en la primera corrida real.

## Blockers
- Build local en verde requiere codegen jOOQ (Docker/DB) — blocker recurrente del proyecto.

## Non-obvious context
- **Hooks bloqueados por seguridad, no por error:** el auto-mode classifier rechaza escribir `.claude/settings.json` con hooks+permisos (self-modification). Por eso quedaron opt-in. El proyecto ya había retirado un SessionStart hook intrusivo tras dogfooding, así que se diseñaron baratos, acotados a `domain/**` y no-bloqueantes.
- **Scope guard:** el pipeline es SOLO dominio puro. Controllers/jOOQ/migraciones/frontend/infra NO pasan por aquí — van con `backend-developer` o directo (`AGENTS.md §0`).
- **Adaptaciones Java:** Stryker→PIT, Jest→Surefire/`mvnd`, `init.sh`→`init.ps1`, dinero `BigDecimal` HALF_EVEN escala 2, reloj inyectado, `@sN` Gherkin → `@DisplayName` JUnit (`should...When...`).
- `feature_list.json`/`project-spec.md` se sembraron NUEVOS (los de signSystem eran de payroll); el backlog es un punto de partida a refinar.
- Para retomar en otra sesión: este worktree (`.claude/worktrees/uncle-bob-harness-migration`) sigue en la rama; `main` no tiene la migración hasta mergear el PR.
