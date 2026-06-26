# Mutación — feature #2 (transaction_categorization_rules)

**Veredicto:** PASS
**Score:** 66/66 = 100% (umbral: 100% sobre líneas nuevas)
**PIT version:** 1.25.5
**Fecha:** 2026-06-26

---

## Resumen por clase

| Clase | Killed/Total | % |
|-------|-------------|---|
| com.myfinanceview.domain.category.TransactionCategorizer | 66/66 | 100% |
| com.myfinanceview.domain.category.CategoryMatch (sealed interface) | 0/0 | N/A — sin lógica mutable |
| com.myfinanceview.domain.category.Matched (record) | 0/0 | N/A — Record junk filter aplicado |
| com.myfinanceview.domain.category.NoMatch (record) | 0/0 | N/A — Record junk filter aplicado |
| com.myfinanceview.domain.category.CategoryRef (record) | 0/0 | N/A — Record junk filter aplicado |
| com.myfinanceview.domain.category.CategorizableTransaction (record) | 0/0 | N/A — Record junk filter aplicado |
| com.myfinanceview.domain.category.CategoryRule (record) | 0/0 | N/A — Record junk filter aplicado |
| com.myfinanceview.domain.category.TransactionKind (enum) | 0/0 | N/A — Enum junk filter aplicado |
| com.myfinanceview.domain.category.InvalidRuleException | 0/0 | N/A — Sin lógica mutada |

Todos los 66 mutantes generados corresponden a `TransactionCategorizer.java`. Los records, enum, sealed interface e `InvalidRuleException` no generaron mutantes (filtros automáticos de PIT: record junk filter, enum junk filter).

---

## Distribución de mutadores (66 mutantes, 66 killed)

| Mutador | Generados | Killed | % |
|---------|-----------|--------|---|
| REMOVE_CONDITIONALS_EQUAL_IF | 23 | 23 | 100% |
| REMOVE_CONDITIONALS_EQUAL_ELSE | 23 | 23 | 100% |
| REMOVE_CONDITIONALS_ORDER_IF | 3 | 3 | 100% |
| REMOVE_CONDITIONALS_ORDER_ELSE | 3 | 3 | 100% |
| TRUE_RETURNS | 5 | 5 | 100% |
| FALSE_RETURNS | 2 | 2 | 100% |
| NULL_RETURNS | 3 | 3 | 100% |
| VOID_METHOD_CALLS | 1 | 1 | 100% |
| CONDITIONALS_BOUNDARY | 3 | 3 | 100% |

---

## Métricas de cobertura

- **Line Coverage (clases mutadas):** 43/44 (98%)
- **Mutations with no coverage:** 0
- **Test strength:** 100%
- **Tests examinados:** 1 suite (`TransactionCategorizerTest`)
- **Tests ejecutados por mutación:** 179 (2.71 promedio por mutante)

---

## Historia: de 88% a 100%

La pasada anterior reportó **FAIL con 58/66 = 88% (8 sobrevivientes)**. Los sobrevivientes correspondían a tres familias de ramas no cubiertas:

| Familia | Sobrevivientes previos | Tests de hardening añadidos por `tdd_craftsman` |
|---------|----------------------|------------------------------------------------|
| Regla con sólo `maxAmount` no-null | S2, S3, S7 | `shouldCategorizeWhenRuleHasOnlyMaxAmountPredicate` |
| Regla con sólo `transactionKinds` no-vacío | S4, S5, S8 | `shouldCategorizeWhenRuleHasOnlyTransactionKindsPredicate` |
| Regla con `transactionKinds=null` sin otro predicado | S1, S6 | `shouldThrowWhenRuleHasNullTransactionKindsAndNoOtherPredicate` |

El `tdd_craftsman` añadió los 3 tests de mutation-hardening (aprobados por el `judge` en segunda revisión). En esta corrida los 8 sobrevivientes previos fueron eliminados: **0 sobrevivientes**.

---

## Conclusión

**Score: 66/66 = 100%. 0 sobrevivientes. BUILD SUCCESS.**

Feature `transaction_categorization_rules` supera el umbral PIT del harness Uncle Bob (100% sobre líneas nuevas). La feature puede pasar a estado `done`.
