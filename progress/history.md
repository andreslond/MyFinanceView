# Bitácora del harness

> Append-only. Una entrada por sesión cerrada. Formato libre pero conciso:
> fecha, feature, qué se hizo, resultado de mutación.

---

## 2026-06-25 — Harness Uncle Bob instalado

**Qué se hizo:**
- Se migró el harness de signSystem (`experiment/uncle-bob-harness`) al
  proyecto, adaptándolo de TypeScript/Jest/Stryker a Java/Maven/JUnit/PIT.
- Se retiró el harness anterior de OpenSpec (skills, comandos `/opsx:*`,
  `scripts/preflight.ps1`); el legado se archivó en `archive/openspec-legacy/`.
- Aún no se ha implementado ninguna feature de dominio por este pipeline.

**Resultado mutation_tester:** N/A (sin feature todavía).

---

## 2026-06-26 — billing_period_resolution (#1) — TDD completo

**Qué se hizo:**
- Pipeline completo conversación → Gherkin → TDD sobre el dominio puro
  `domain/billing`. 8 decisiones de diseño conversadas y cerradas en
  `project-spec.md`; 13 escenarios `@s1..@s13` destilados en
  `features/billing_period_resolution.feature` (puerta humana cubierta por
  aprobación previa de las decisiones + auditoría del lead; se corrigió el
  bug de contrato `@s8` → `[2024-01-30, 2024-02-29]`).
- 10 ciclos Rojo→Verde→Refactor. Producción: `BillingPeriod` (record),
  `BillingPeriodResolver` (estático, puro), `InvalidCutDayException` y la
  base `DomainException`. Clamping determinista documentado; anti-adivinanza
  vía excepción para `cutDay ∉ [1..31]`.

**Resultado suite:** 14/14 GREEN (tests puros, sin Testcontainers); `init.ps1` sin `[FAIL]`.
**Judge:** APPROVED (tras corregir trazabilidad de `feature_list.json`/`history.md`).
**Resultado mutation_tester:** PASS — **100%** (11/11 mutantes muertos, test strength 100%, 0 sobrevivientes). Ver `progress/mutation_billing_period_resolution.md`.
**Fix de entorno:** se desbloqueó el codegen jOOQ (Postgres en Docker) y se bumpeó `pitest-maven` 1.17.4 → **1.25.5** + `pitest-junit5-plugin` 1.2.1 → 1.2.3 (ASM 9.8, soporta bytecode JDK 25). Primera corrida real de PIT en el proyecto.
**Status final:** `done`.

---

## 2026-06-26 — transaction_categorization_rules (#2) — DONE

**Qué se hizo:**
- Pipeline completo conversación → Gherkin → TDD → judge → mutación sobre
  `domain/category`. 11 decisiones cerradas en `project-spec.md`; 21 escenarios
  `@s1..@s21` (puerta humana aprobada por el operador). Auditoría del lead: sin bugs;
  literales de `TransactionKind` verificados contra el enum DB `transaction_type` (V001).
- Producción pura (sealed `CategoryMatch` + `Matched`/`NoMatch`, `CategoryRef`,
  `CategorizableTransaction`, `CategoryRule`, `TransactionKind` enum,
  `InvalidRuleException`, `TransactionCategorizer` estático). Semántica AND de predicados;
  `contains` case-insensitive; rango de monto inclusive con `compareTo`; conflicto de
  categorías → `NoMatch` (anti-adivinanza); catch-all / patrón blank / min>max →
  `InvalidRuleException`.

**Resultado suite:** GREEN (tests puros). `init.ps1` sin `[FAIL]`.
**Judge:** APPROVED (a la primera; re-review tras mutation-hardening también APPROVED).
**Resultado mutation_tester:** primera corrida 88% (8 sobrevivientes en ramas single-predicate / null-kinds) → tdd_craftsman añadió tests de endurecimiento → **PASS 100% (66/66)**, test strength 100%.
**Status final:** `done`.
