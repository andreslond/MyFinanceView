# Prueba de mutación — validar que los tests muerden

> "Mutation testing is resource-heavy, but the ROI on code correctness is
> worth every cycle." Una suite verde dice "no explota". Una suite verde
> + mutación al 100% dice "los tests muerden".

## El problema que resuelve

Un test sin asserts fuertes pasa siempre y no protege nada. La mutación lo
mide al revés: introduce un defecto pequeño y observa la suite.

- Si **algún test falla** → el mutante está **muerto** (killed). Bien.
- Si **todos los tests pasan** → el mutante **sobrevive** (survived).
  Mal: hay un agujero.

**Puntuación de mutación** = `killed / total`.

## La herramienta: PIT (pitest-maven)

La config vive en `backend/pom.xml`, plugin `org.pitest:pitest-maven`
(con `pitest-junit5-plugin`). Grupo de mutadores: `STRONGER`.

Ejecutar:

```bash
# Acotado a las clases de una feature (preferido durante TDD)
mvnd -q -o org.pitest:pitest-maven:mutationCoverage \
  -DtargetClasses='com.myfinanceview.domain.<area>.*' \
  -DtargetTests='com.myfinanceview.domain.<area>.*'

# Todo el dominio (completo pero costoso)
mvnd -q org.pitest:pitest-maven:mutationCoverage
```

Reporte HTML en `backend/target/pit-reports/`. Resumen en consola.

## El umbral

- Por defecto, la feature exige **100% de mutantes muertos sobre las
  líneas nuevas o tocadas** por esa feature.
- Para código heredado no tocado por la feature, no se exige umbral (se
  mide informativo, no bloquea).
- Un mutante **equivalente** (no cambia el comportamiento observable —
  p. ej. una rama muerta por guards previos) puede excluirse, **solo**
  con justificación explícita escrita en `progress/mutation_<name>.md`.
  Abusar de esta vía es hacer trampa al juez.

## Quién hace qué

- El `mutation_tester` **mide** y reporta. No edita código.
- Un mutante sobreviviente es trabajo del `tdd_craftsman`: escribe el
  test rojo que lo mata y vuelve a pasar por el `judge`. Es el ciclo de
  mejora compute-bound: el CPU encuentra el hueco, el artesano lo tapa
  con un test.

## Mutadores que importan en este dominio

PIT incluye muchos. Con el grupo `STRONGER`, los que más muerden en
código financiero y de reglas de período:

| Mutador                    | Ejemplo                                          | Por qué importa                                        |
|----------------------------|--------------------------------------------------|--------------------------------------------------------|
| CONDITIONALS_BOUNDARY      | `date.isAfter(cut)` → `date.isAfterOrEqual(cut)` | Cualquier umbral de fecha o corte                      |
| NEGATE_CONDITIONALS        | `amount.compareTo(ZERO) > 0` → `<= 0`            | Validaciones de signo de dinero                        |
| MATH                       | `total.add(fee)` → `total.subtract(fee)`          | Cálculo de totales y saldos                            |
| INCREMENTS                 | `i++` → `i--`                                    | Iteraciones sobre colecciones de movimientos           |
| VOID_METHOD_CALLS          | (borrar llamada a método void)                   | Detecta efectos sin observación                        |
| EMPTY_RETURNS              | `return result` → `return Optional.empty()`       | Flujos que devuelven Optional                          |
| NULL_RETURNS               | `return record` → `return null`                   | Métodos que retornan objetos de dominio                |
| PRIMITIVE_RETURNS          | `return count` → `return 0`                       | Métodos numéricos sin asserts de valor                 |
| REMOVE_CONDITIONALS        | (borrar toda la condición)                       | Reglas que no tienen tests de rama completa            |

Si PIT reporta un sobreviviente de `MATH` en el motor de cálculo de
saldos o períodos, **siempre** es un test débil. El test que lo mata
suele ser un caso con valores distintos por rama — y hay que comparar
con `isEqualByComparingTo`, nunca con `equals` de `BigDecimal`.
