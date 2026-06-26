# Verification — cómo demostrar que funciona

> Antes de declarar una feature `done`, demuestra **estado final
> verificable**. Si no puedes ejecutarlo, no está hecho.

## La secuencia obligatoria al cierre de una feature

```powershell
# 1. Init verde — entorno y feature_list coherentes
powershell.exe -NoProfile -ExecutionPolicy Bypass -File init.ps1

# 2. Tests del dominio en verde (puro, sin Testcontainers, rápido)
mvnd -q test -Dtest='com.myfinanceview.domain.**'

# 3. Mutación al 100% sobre líneas tocadas
mvnd -q -o org.pitest:pitest-maven:mutationCoverage `
  -DtargetClasses='com.myfinanceview.domain.<area>.*' `
  -DtargetTests='com.myfinanceview.domain.<area>.*'
```

Las tres tienen que pasar. Si una falla, la feature NO está `done`.

## Lo que el `judge` también verifica

**Tres greps mecánicos de pureza**, sobre
`backend/src/main/java/com/myfinanceview/domain/`:

```bash
grep -REn "import (org\.jooq|org\.springframework|java\.sql|java\.io|.*\.db\.|.*Repository)"
# debe estar vacío — contaminación de IO

grep -REn "now\(\)|currentTimeMillis|new Date\("
# debe estar vacío — reloj implícito

grep -REn "System\.(out|err)\.|printStackTrace"
# debe estar vacío — logs en el dominio
```

Cualquier coincidencia es rechazo automático sin lectura adicional del diff.

**Grep por cada `@s`** del `.feature`:

```bash
grep -REn '@s<N>' backend/src/test/java/com/myfinanceview/domain
```

Debe devolver al menos una coincidencia por tag. Si un `@s` no aparece
en ningún test, el `judge` rechaza.

## Golden master (cuando aplique)

Para cálculos con dinero (saldos de ahorro, resolución de períodos de
facturación, totales de transacciones) la spec puede pedir paridad con
valores de referencia. Cuando se definen esos fixtures:

- Colócalos en
  `backend/src/test/resources/fixtures/` (JSON o CSV) o como
  `@MethodSource` / `@CsvSource` en el propio test parametrizado.
- El test itera los fixtures y compara con `isEqualByComparingTo` (no
  `equals` de `BigDecimal`) y verifica la escala si la spec la fija.
- Si un fixture diverge del motor, **no ajustes el motor**: documenta
  el porqué en `project-spec.md` y reabre la conversación con el
  `spec_partner`. El motor no se dobla para silenciar un test.

Ejemplo de test parametrizado golden master:

```java
@ParameterizedTest(name = "@fixture {0}")
@MethodSource("savingsFixtures")
void shouldMatchGoldenMasterForSavingsProgress(
        String label, SavingsInput input, BigDecimal expected) {
    var result = SavingsCalculator.calculateProgress(input);
    assertThat(result.progressAmount())
        .isEqualByComparingTo(expected);
}
```

## Reporting al lead

El `tdd_craftsman` no presenta el diff por chat. Su única respuesta es
una línea con la referencia al `progress/tdd_<name>.md`. El humano (o el
lead) abre el archivo y los artefactos en disco para verificar:

- `features/<name>.feature` — los escenarios aprobados.
- `progress/tdd_<name>.md` — bitácora + mapa `@s → test`.
- `progress/judge_<name>.md` — veredicto.
- `progress/mutation_<name>.md` — score PIT y mutantes sobrevivientes
  (si los hubo), con justificación de equivalentes si se excluyeron.

Si los cuatro archivos cuentan la historia completa sin ambigüedad, la
feature está documentada como debe.
