# AGENTS.md — Mapa de navegación para agentes (harness Uncle Bob)

> Punto de entrada para cualquier agente que trabaje el **dominio puro** de
> MyFinanceView con el harness Uncle Bob. NO es una biblia: es un **mapa**.
> Lee solo lo que necesites cuando lo necesites (divulgación progresiva).
>
> Flujo: conversación → Gherkin → TDD → review → mutación.
> Ver `docs/uncle-bob/workflow.md`.
>
> Este harness reemplaza al flujo OpenSpec (`/opsx:*`) anterior. El legado
> de OpenSpec vive archivado en `archive/openspec-legacy/` (no autoritativo).

---

## 0. Cuándo aplica este harness (y cuándo NO)

**Aplica** cuando vas a implementar **lógica de dominio pura** bajo
`backend/src/main/java/com/myfinanceview/domain/**`: cálculos, reglas,
resolución de períodos, categorización, matching, progreso de metas.

**NO aplica** (van por su camino normal, sin este pipeline) para:
controllers/REST, repositorios jOOQ, migraciones Flyway/SQL, auth, config,
frontend, infra/backups, exploración o preguntas de solo lectura, y
correcciones de formato/typo. Para esas tareas, trabaja directo o usa el
subagente que corresponda (`backend-developer`, etc.).

## 1. Antes de empezar (obligatorio para trabajo SDD)

1. Ejecuta `init.ps1`
   (`powershell.exe -NoProfile -ExecutionPolicy Bypass -File init.ps1`) y
   verifica que termina sin `[FAIL]`. Un `[WARN]` de codegen jOOQ en
   checkout frío es esperable.
2. Lee `progress/current.md` para entender en qué estado quedó la última
   sesión.
3. Lee `feature_list.json`. Toda feature con `"sdd": true` recorre el
   pipeline de 5 fases — ver `docs/uncle-bob/workflow.md` y §4.
4. Lee `docs/uncle-bob/workflow.md` antes de coordinar nada.

## 2. Mapa del repositorio (harness)

| Archivo / carpeta              | Qué contiene                                                                 | Cuándo leerlo |
|--------------------------------|------------------------------------------------------------------------------|---------------|
| `feature_list.json`            | Lista de features con estado y `sdd:true/false`                              | Siempre, al empezar |
| `progress/current.md`          | Estado de la sesión activa                                                   | Siempre |
| `progress/history.md`          | Bitácora append-only                                                         | Si necesitas contexto histórico |
| `project-spec.md`              | Spec conversada (puntero a la autoridad de diseño)                           | Antes de Gherkin o TDD |
| `features/<name>.feature`      | Escenarios Gherkin (contrato ejecutable que el humano aprueba)               | Antes del TDD |
| `docs/uncle-bob/workflow.md`   | Pipeline completo y los insights de cada fase                                | Antes de coordinar |
| `docs/uncle-bob/tdd.md`        | Tres Leyes del TDD; ciclo Rojo-Verde-Refactor                               | Antes de codear |
| `docs/uncle-bob/gherkin.md`    | Cómo escribir `.feature`; cómo se mapea `@s → @DisplayName`                  | Antes de redactar/leer escenarios |
| `docs/uncle-bob/mutation-testing.md` | Por qué/cómo; umbral; uso de PIT                                       | Antes de validar la suite |
| `docs/uncle-bob/architecture.md` | Qué significa "buen trabajo" en el dominio puro (pureza, dinero, tiempo)   | Antes de implementar |
| `docs/uncle-bob/conventions.md`| Reglas de estilo, nombres, errores, dinero (BigDecimal)                      | Antes de codear |
| `docs/uncle-bob/verification.md` | Cómo verificar que tu trabajo funciona                                     | Antes de declarar `done` |
| `CHECKPOINTS.md`               | Criterios objetivos de "estado final correcto"                              | Para auto-evaluarte |
| `backend/pom.xml` (`pitest-maven`) | Configuración base de PIT (mutación)                                    | Fase de mutación |
| `init.ps1`                     | Verificación del entorno y coherencia del `feature_list.json`               | Al empezar y al cerrar |
| `.claude/agents/`              | `craftsman_lead`, `spec_partner`, `gherkin_author`, `tdd_craftsman`, `judge`, `mutation_tester` | Si orquestas |
| **Autoridad de diseño (fuera del harness):** | | |
| `SPEC.md`                      | Visión, stack, decisiones clave                                             | Antes de cualquier feature |
| `docs/data-model.md`           | Esquema `myfinance` + migraciones                                          | Si dudas de modelo |
| `docs/base-standards.md`, `docs/backend-standards.md` | Principios transversales y reglas Java/jOOQ                     | Estilo y reglas duras |
| `plans/`                       | Planes por feature                                                          | Si la feature tiene plan |

## 3. Reglas duras (no negociables)

- **Scope:** solo `backend/src/main/java/com/myfinanceview/domain/**` y
  `backend/src/test/java/com/myfinanceview/domain/**`. Nada fuera.
- **Una sola feature a la vez.** No mezcles cambios de varias tareas en la
  misma sesión.
- **No declares una feature `done`** sin tests verdes Y umbral de mutación
  superado. Ejecuta `init.ps1` y, al cierre, PIT sobre lo tocado.
- **No saltes la conversación de spec ni la destilación Gherkin.** Toda
  feature con `"sdd": true` pasa por `spec_partner` y `gherkin_author`.
- **No saltes la puerta de aprobación humana** sobre los `.feature`. El
  `craftsman_lead` detiene el flujo en `spec_ready` y espera.
- **TDD estricto: un test a la vez.** Nada de producción sin un test rojo
  que la pida (`docs/uncle-bob/tdd.md`).
- **Modelo de subagentes:** Sonnet siempre. Solo el lead corre Opus.
- **Dinero `BigDecimal` HALF_EVEN escala 2; tiempo `OffsetDateTime` UTC;
  ids `UUID`; DTOs `record`.** Son reglas del proyecto (`docs/base-standards.md`).
- **Documenta lo que haces** en `progress/current.md` mientras trabajas.

## 4. Flujo de trabajo (pipeline)

Ver `docs/uncle-bob/workflow.md` para el detalle.

## 5. Cierre de sesión

Antes de terminar:

1. Ejecuta `init.ps1` — sin `[FAIL]`.
2. Corre PIT sobre lo tocado — supera el umbral (sobre líneas nuevas).
3. Si la feature está acabada: marca `status: "done"` en `feature_list.json`.
4. Mueve el resumen de `progress/current.md` al final de `progress/history.md`.
5. Vacía `progress/current.md` dejando solo la plantilla.
6. No dejes archivos temporales ni TODOs sin contexto.

## 6. Si te bloqueas

- Relee la sección relevante de `docs/uncle-bob/`.
- Si la herramienta no hace lo que esperas, **no inventes un workaround**:
  documenta el bloqueo en `progress/current.md` y para la sesión.
