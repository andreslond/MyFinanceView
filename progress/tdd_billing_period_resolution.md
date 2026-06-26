# TDD Log — billing_period_resolution (#1)

**Status:** green (awaiting judge + mutation_tester)
**Test class:** `BillingPeriodResolverTest`
**Artifacts created:**
- `backend/src/main/java/com/myfinanceview/domain/DomainException.java`
- `backend/src/main/java/com/myfinanceview/domain/billing/BillingPeriod.java`
- `backend/src/main/java/com/myfinanceview/domain/billing/InvalidCutDayException.java`
- `backend/src/main/java/com/myfinanceview/domain/billing/BillingPeriodResolver.java`
- `backend/src/test/java/com/myfinanceview/domain/billing/BillingPeriodResolverTest.java`

---

## Ciclos Rojo → Verde → Refactor

### Ciclo 1 — @s1 (fecha == cutDay → período actual)
- **ROJO:** `BillingPeriodResolverTest#shouldReturnCurrentPeriodWhenDateEqualsCutDay` — falla porque `BillingPeriod` y `BillingPeriodResolver` no existen.
- **VERDE:** Creados `BillingPeriod.java` (record) y `BillingPeriodResolver.java` (retorno hardcodeado para @s1: `[2026-04-16, 2026-05-15]`).
- **REFACTOR:** Sin cambios (código mínimo ya claro).

### Ciclo 2 — @s2 (fecha == cutDay+1 → período siguiente)
- **ROJO:** `BillingPeriodResolverTest#shouldReturnNextPeriodWhenDateIsOneDayAfterCutDay` — falla porque el hardcode retorna siempre el período de @s1.
- **VERDE:** Implementación completa del algoritmo en `BillingPeriodResolver`:
  - Comparación `dayOfMonth <= cutDay` para determinar mes de cierre.
  - `effectiveCutDay(cutDay, YearMonth)` = `min(cutDay, lastDayOfMonth)`.
  - `end` y `start` calculados con clamping determinista.
- **REFACTOR:** Sin duplicación; código limpio en primera versión.

### Ciclo 3 — @s3 (fecha posterior al corte, mismo mes)
- **ROJO:** `BillingPeriodResolverTest#shouldReturnNextPeriodWhenDateIsAfterCutDayInSameMonth` — escrito; **ya pasa** con la implementación de @s2 (mismo camino de código).
- **VERDE:** Sin cambio de producción.
- **REFACTOR:** —

### Ciclo 4 — @s4 y @s5 (clamping: cutDay 31 en febrero)
- **ROJO:** `shouldClampEndToFeb28WhenCutDay31AndFebruaryShort` + `shouldClampEndToFeb28WhenCutDay31AndDateIsLastDayOfFeb` — escritos; **ya pasan** gracias a `effectiveCutDay` ya implementado.
- **VERDE:** Sin cambio de producción.
- **REFACTOR:** —

### Ciclo 5 — @s6 (cutDay 31 en abril)
- **ROJO:** `shouldClampEndToApr30WhenCutDay31AndAprilIsShort` — ya pasa con implementación existente.
- **VERDE/REFACTOR:** —

### Ciclo 6 — @s7 y @s8 (corte 29 en febrero no bisiesto / bisiesto)
- **ROJO:** `shouldClampBothBoundsWhenCutDay29AndNonLeapFebruary` + `shouldUseDay29WhenCutDay29AndLeapYearFebruary` — ya pasan.
- **VERDE/REFACTOR:** —

### Ciclo 7 — @s9 y @s10 (cruce de año)
- **ROJO:** `shouldCrossYearBoundaryWhenJanuaryDateBeforeCutDay` + `shouldStartOnJan1WhenPrevMonthIsDecAnd31ClampsToDec31ThenPlusOne` — ya pasan.
- **VERDE/REFACTOR:** —

### Ciclo 8 — @s11 (cutDay 1, borde inferior válido)
- **ROJO:** `shouldReturnCorrectPeriodWhenCutDay1IsLowerBound` — ya pasa.
- **VERDE/REFACTOR:** —

### Ciclo 9 — @s12 y @s13 (cutDay fuera de rango → InvalidCutDayException)
- **ROJO:** `shouldThrowInvalidCutDayExceptionWhenCutDayIsZero` + `shouldThrowInvalidCutDayExceptionWhenCutDayIs32` — fallan porque `InvalidCutDayException` no existe (error de compilación).
- **VERDE:**
  - Creado `DomainException.java` (base abstracta del dominio).
  - Creado `InvalidCutDayException.java` (mensaje: `"cutDay N is out of valid range [1..31]. Provide a value between 1 and 31 inclusive."`).
  - Añadida validación en `BillingPeriodResolver.resolve()`: `if (cutDay < 1 || cutDay > 31) throw new InvalidCutDayException(cutDay)`.
- **REFACTOR:** Eliminado comentario stale de @s1 hardcode; corregido typo en `@DisplayName` de @s4 ("Dec28" → "Feb28").

### Ciclo 10 — Idempotencia/Determinismo
- **ROJO → VERDE:** `shouldReturnSamePeriodWhenCalledTwiceWithSameInput` — pasa inmediatamente (función pura).
- **REFACTOR:** —

---

## Trazabilidad @s → test

| Escenario | Test en `BillingPeriodResolverTest` |
|---|---|
| @s1 | `shouldReturnCurrentPeriodWhenDateEqualsCutDay` |
| @s2 | `shouldReturnNextPeriodWhenDateIsOneDayAfterCutDay` |
| @s3 | `shouldReturnNextPeriodWhenDateIsAfterCutDayInSameMonth` |
| @s4 | `shouldClampEndToFeb28WhenCutDay31AndFebruaryShort` |
| @s5 | `shouldClampEndToFeb28WhenCutDay31AndDateIsLastDayOfFeb` |
| @s6 | `shouldClampEndToApr30WhenCutDay31AndAprilIsShort` |
| @s7 | `shouldClampBothBoundsWhenCutDay29AndNonLeapFebruary` |
| @s8 | `shouldUseDay29WhenCutDay29AndLeapYearFebruary` |
| @s9 | `shouldCrossYearBoundaryWhenJanuaryDateBeforeCutDay` |
| @s10 | `shouldStartOnJan1WhenPrevMonthIsDecAnd31ClampsToDec31ThenPlusOne` |
| @s11 | `shouldReturnCorrectPeriodWhenCutDay1IsLowerBound` |
| @s12 | `shouldThrowInvalidCutDayExceptionWhenCutDayIsZero` |
| @s13 | `shouldThrowInvalidCutDayExceptionWhenCutDayIs32` |
| idempotencia | `shouldReturnSamePeriodWhenCalledTwiceWithSameInput` |

**Total tests:** 14 (13 escenarios + 1 idempotencia)

---

## Cierre
- `mvnd -q -o test -Dtest='BillingPeriodResolverTest'` → **GREEN** (14 tests)
- `mvnd -q test "-Dtest=com.myfinanceview.domain.**"` → **GREEN** (suite completa del dominio)
- `init.ps1` → **sin [FAIL]**
