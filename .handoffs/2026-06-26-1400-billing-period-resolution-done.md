---
created: 2026-06-26T14:00:58Z
branch: feat/billing-period-resolution
worktree: .claude/worktrees/billing-period-resolution
mode: paused
---

# billing_period_resolution (#1) — DONE, sin commitear

## Next step
Commitear el trabajo en `feat/billing-period-resolution` (commit explícito; el operador lo decide) y abrir PR contra `main`; o arrancar la feature #2 `transaction_categorization_rules`.

## Goal
Implementar la primera feature de dominio del harness Uncle Bob por el pipeline completo (conversación → Gherkin → TDD → judge → mutación PIT), scopeada a `domain/billing`.

## Done this session
- Pipeline completo de 5 fases para `billing_period_resolution`: **judge APPROVED + PIT 100%** (11/11 mutantes). Feature marcada `done` en `feature_list.json`.
- Producción pura: `BillingPeriod` (record), `BillingPeriodResolver` (estático), `InvalidCutDayException`, base `DomainException`. 14 tests verdes, init.ps1 sin `[FAIL]`.
- Puerta humana: las 8 decisiones de diseño las aprobó el operador; el lead auditó el `.feature` y corrigió 1 bug de contrato (`@s8` año bisiesto → `[2024-01-30, 2024-02-29]`).
- **Fix de entorno (infra, fuera del dominio):** codegen jOOQ desbloqueado vía Postgres en Docker (`docker/docker-compose.yml`, V001–V005 en :5433); `pitest-maven` 1.17.4 → **1.25.5** + `pitest-junit5-plugin` 1.2.1 → 1.2.3 en `backend/pom.xml` (ASM 9.8 lee bytecode JDK 25). Primera corrida real de PIT.

## Working tree state
- **Committed:** nada — todo el trabajo está SIN commitear en la rama.
- **Modified:** `backend/pom.xml` (bump PIT), `feature_list.json` (#1 → done), `project-spec.md` (sección billing), `progress/current.md`, `progress/history.md`.
- **Untracked:** `domain/billing/{BillingPeriod,BillingPeriodResolver,InvalidCutDayException}.java`, `domain/DomainException.java`, el test `domain/billing/BillingPeriodResolverTest.java`, `features/billing_period_resolution.feature`, `progress/{tdd,judge,mutation}_billing_period_resolution.md`.
- **Red tests:** ninguno. Suite billing 14/14 verde.

## Pending
- Commit + PR (explícito).
- Feature #2 `transaction_categorization_rules` (siguiente `pending`).
- `main` LOCAL del checkout principal está 3 commits detrás de `origin/main` → `git pull --ff-only` allí cuando se pueda (no afecta esta rama).

## Blockers
- Ninguno. (El codegen jOOQ requiere el contenedor Postgres `myfinance-postgres-local` arriba para compilar en frío; está corriendo.)

## Non-obvious context
- **PIT requería JDK-25 fix:** 1.17.4 (ASM 9.7.1) no arranca en JDK 25 (class major version 69). Override de ASM a 9.8 en `<dependencies>` del plugin NO funcionó (PIT usa su ASM interno); la solución fue bumpear el plugin a 1.25.5. Documentado en `progress/mutation_billing_period_resolution.md`.
- **Codegen jOOQ:** `mvnd -P codegen generate-sources` contra `:5433` genera `backend/target/generated-sources/jooq` (gitignored). El módulo no compila en frío sin esto, aunque el dominio puro no importe jOOQ.
- **Decisión de método:** la acceptance dice `resolveBillingPeriod`; se implementó como `BillingPeriodResolver.resolve(int, LocalDate)` por legibilidad. Bajo riesgo; revertible.
- **Deuda pre-existente (no de esta feature):** account/category/merchant/transaction importan jOOQ/Spring dentro de `domain/**` (anotado por el judge). Candidata a cleanup separado.
