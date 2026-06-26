# TDD Log — transaction_categorization_rules (#2)

> Bitácora ciclo a ciclo. Mapa `@s → test` al final.

## Sesión 2026-06-26

### Ciclo 0 — Scaffolding mínimo (RED → no compila)

**Objetivo:** escribir el primer test (@s1) que falle porque las clases de producción no existen.

**Test escrito:** `@s1 — shouldReturnMatchedWhenSingleMerchantRuleMatches`

**Mínimo de producción para compilar (VERDE de @s1):**
- `TransactionKind` enum (6 literales del DB enum)
- `CategoryRef(UUID id, String name)` record
- `CategorizableTransaction(String descriptor, BigDecimal amount, TransactionKind type, String currency)` record  
- `CategoryRule(UUID id, String merchantPattern, BigDecimal minAmount, BigDecimal maxAmount, Set<TransactionKind> transactionKinds, CategoryRef category)` record
- `CategoryMatch` sealed interface
- `Matched(CategoryRef category)` record
- `NoMatch()` record
- `InvalidRuleException extends DomainException`
- `TransactionCategorizer.categorize(CategorizableTransaction, List<CategoryRule>)` con implementación mínima

---

## Mapa @s → test

| @s  | Test method                                                                 | Resultado |
|-----|-----------------------------------------------------------------------------|-----------|
| @s1  | shouldReturnMatchedWhenSingleMerchantRuleMatches                           | ✅ VERDE  |
| @s2  | shouldReturnMatchedWhenPatternIsLowercaseAndDescriptorIsUppercase          | ✅ VERDE  |
| @s3  | shouldReturnNoMatchWhenDescriptorDoesNotContainPattern                     | ✅ VERDE  |
| @s4  | shouldReturnNoMatchWhenRuleListIsEmpty                                     | ✅ VERDE  |
| @s5  | shouldReturnMatchedWhenMultipleRulesMatchSameCategory                      | ✅ VERDE  |
| @s6  | shouldReturnNoMatchWhenMultipleRulesMatchDifferentCategories               | ✅ VERDE  |
| @s7  | shouldReturnMatchedWhenAmountIsWithinRange                                 | ✅ VERDE  |
| @s8  | shouldReturnNoMatchWhenAmountExceedsMaxAmount                              | ✅ VERDE  |
| @s9  | shouldReturnMatchedWhenAmountEqualsMinAmountBoundary                       | ✅ VERDE  |
| @s10 | shouldReturnMatchedWhenAmountEqualsPointConstraint                         | ✅ VERDE  |
| @s11 | shouldReturnMatchedWhenTransactionKindMatchesRule                          | ✅ VERDE  |
| @s12 | shouldReturnNoMatchWhenTransactionKindDiffersFromRule                      | ✅ VERDE  |
| @s13 | shouldReturnMatchedWhenBothMerchantPatternAndMinAmountPredicatesMet        | ✅ VERDE  |
| @s14 | shouldReturnNoMatchWhenMerchantMatchesButAmountBelowMinThreshold           | ✅ VERDE  |
| @s15 | shouldThrowInvalidRuleExceptionWhenRuleHasNoPredicates                     | ✅ VERDE  |
| @s16 | shouldThrowInvalidRuleExceptionWhenMerchantPatternIsBlank                  | ✅ VERDE  |
| @s17 | shouldThrowInvalidRuleExceptionWhenMerchantPatternIsEmpty                  | ✅ VERDE  |
| @s18 | shouldThrowInvalidRuleExceptionWhenMinAmountExceedsMaxAmount               | ✅ VERDE  |
| @s19 | shouldReturnIdenticalResultsWhenCategorizingTwiceWithSameInput             | ✅ VERDE  |
| @s20 | shouldReturnMatchedWhenAmountIsZero                                        | ✅ VERDE  |
| @s21 | shouldReturnMatchedWhenTransactionKindsIsEmptyInRule                       | ✅ VERDE  |

---

## Mutation hardening — sesión 2026-06-26

**Trigger:** PIT falló con 88% (58/66) tras judge APPROVED. 8 sobrevivientes reales, 3 familias.

**Tests añadidos** en `@Nested SinglePredicateAndNullKindsHardeningTest`:

| Test añadido | Sobrevivientes matados | Razon del gap |
|---|---|---|
| `shouldMatchWhenRuleHasOnlyMaxAmountPredicate` | S2, S3, S7 | Ningún test previo usaba `maxAmount` como único predicado activo (todos combinaban con `merchantPattern`) |
| `shouldNotMatchWhenAmountExceedsMaxAmountOnlyRule` | S3, S7 (refuerzo) | Cubre la rama else del `hasMax` con amount > maxAmount en regla solo-maxAmount |
| `shouldMatchWhenRuleHasOnlyTransactionKindsPredicate` | S4, S5, S8 | Ningún test previo usaba `transactionKinds` como único predicado (siempre combinado con pattern) |
| `shouldNotMatchWhenTransactionKindDiffersFromKindsOnlyRule` | S4, S5, S8 (refuerzo) | Verifica la rama de rechazo para regla solo-kinds |
| `shouldThrowWhenRuleHasNullTransactionKindsAndNoOtherPredicate` | S1, S6 | Ejercita `transactionKinds=null` explícito (distinto de `emptySet`) en la validación sin otros predicados |
| `shouldMatchWhenRuleHasPatternAndNullTransactionKinds` | S1, S6 (refuerzo) | Regla con pattern + `kinds=null` → no filtra por tipo (null = sin restricción), coverage rama null en `ruleMatches` |

**Resultado final:** 66/66 killed = **100%** (0 sobrevivientes). Build SUCCESS.

**Suite de dominio:** `com.myfinanceview.domain.**` → verde sin regresiones.

**Código de producción modificado:** ninguno. Todos los sobrevivientes eran gaps de test, no bugs.
