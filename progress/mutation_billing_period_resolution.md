# Mutación — feature #1 (billing_period_resolution)

**Veredicto:** PASS
**Score:** 11/11 killed = 100% (umbral: 100% sobre líneas nuevas)
**PIT version:** 1.25.5 (pitest-junit5-plugin 1.2.3, ASM 9.8+ — JDK 25 compatible)
**Fecha:** 2026-06-26
**Comando:**
```
mvnd org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses='com.myfinanceview.domain.billing.*' \
  -DtargetTests='com.myfinanceview.domain.billing.*'
```
Ejecutado desde: `backend/` en el worktree `billing-period-resolution`.

---

## Resumen por clase

| Clase | Mutaciones | Killed | Score |
|---|---|---|---|
| `com.myfinanceview.domain.billing.BillingPeriodResolver` | 11 | 11 | 100% |
| `com.myfinanceview.domain.billing.BillingPeriod` (record) | 0 | — | n/a (sin lógica mutable) |
| `com.myfinanceview.domain.billing.InvalidCutDayException` | 0 | — | n/a (sin lógica mutable) |

### Desglose por mutador (BillingPeriodResolver)

| Mutador | Líneas afectadas | Generadas | Killed | Score |
|---|---|---|---|---|
| `CONDITIONALS_BOUNDARY` | 32 (×2), 39 | 3 | 3 | 100% |
| `REMOVE_CONDITIONALS_ORDER_ELSE` | 32 (×2), 39 | 3 | 3 | 100% |
| `REMOVE_CONDITIONALS_ORDER_IF` | 32 (×2), 39 | 3 | 3 | 100% |
| `NULL_RETURNS` | 50 | 1 | 1 | 100% |
| `PRIMITIVE_RETURNS` | 58 | 1 | 1 | 100% |
| **Total** | | **11** | **11** | **100%** |

Los 11 mutantes fueron matados por tests de `BillingPeriodResolverTest`. Los mutadores más exigentes (`CONDITIONALS_BOUNDARY` sobre la validación `cutDay < 1 || cutDay > 31` y el ternario de mes de corte) fueron cubiertos por los tests de boundary explícitos (cutDay = 0, 1, 31, 32) y los casos de clamping.

---

## Cobertura de líneas: 12/13 (92%)

La línea no cubierta es el **constructor privado** de `BillingPeriodResolver` (línea 16):

```java
private BillingPeriodResolver() {}
```

`BillingPeriodResolver` es una clase utilitaria `final` con únicamente métodos `static`. El constructor privado es un guardia de diseño (evita instanciación accidental); es **estructuralmente inalcanzable** sin reflexión explícita. PIT no genera ningún mutante para él (`NO_COVERAGE 0` — correctamente filtrado por el plugin). Esta línea no afecta el score de mutación ni el umbral.

**Clasificación:** equivalente estructural / no-mutable. No requiere test adicional.

---

## Mutantes sobrevivientes

Ninguno. 0 survived, 0 timed_out, 0 no_coverage.

---

## Contexto de la feature

- **Judge:** APPROVED (2026-06-26, RE-REVIEW aprobado — ver `progress/judge_billing_period_resolution.md`)
- **Suite dominio:** 14/14 GREEN (`BillingPeriodResolverTest`)
- **Test strength (PIT):** 100%
- **init.ps1:** sin [FAIL]
- **BUILD SUCCESS** (5.6 s wall clock)
