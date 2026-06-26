# El flujo Uncle Bob (Harness Engineering, edición artesano)

> Esta rama replanta el dominio puro de MyFinanceView alrededor del
> proceso que Robert C. Martin describe en su hilo: **conversar la spec,
> destilarla en escenarios Gherkin, tallar el código con TDD estricto,
> podar con juicio y validar con prueba de mutación**.

## El pipeline de un vistazo

```
pending
  │  spec_partner — CONVERSACIÓN  ───────────────►  project-spec.md
  │
  │  gherkin_author — DESTILACIÓN ───────────────►  features/<name>.feature
  ▼  ⏸  PUERTA HUMANA: el humano aprueba los escenarios (el contrato)
  │
in_progress
  │  tdd_craftsman — ROJO → VERDE → REFACTOR ────►  backend/src/main/java/com/myfinanceview/domain/ + tests
  │
  │  judge — REVIEW ─────────────────────────────►  progress/judge_<name>.md
  │
  │  mutation_tester — MUTACIÓN (PIT) ────────────►  progress/mutation_<name>.md
  ▼
done
```

Una sola feature a la vez. Una sola puerta de aprobación humana: sobre los
escenarios Gherkin, **antes** de escribir producción.

## Por qué este orden

1. **La spec nace de una conversación, no de un dictado.** El humano no
   entrega un documento cerrado; debate con el `spec_partner` decisiones,
   alternativas descartadas y casos límite. El resultado vive en
   `project-spec.md` (que referencia a la autoridad de diseño — SPEC.md,
   docs/, plans/ — sin duplicarla).

2. **Gherkin convierte la prosa en un contrato ejecutable.** Cada
   comportamiento se vuelve un `Scenario` con `Given/When/Then`
   verificable. Es lo que el humano firma. Después, la ambigüedad es un
   bug del contrato, no del código.

3. **La puerta humana va sobre el contrato, no sobre el código.** Aprobar
   tarde es caro. Aprobar el `.feature` es el punto de máximo
   apalancamiento.

4. **TDD estricto: un test a la vez.** No se escriben todos los tests por
   adelantado. Se vive el ciclo pequeño: un test rojo → el mínimo verde →
   refactor en verde. Ver `docs/uncle-bob/tdd.md`. Código que ningún test
   pidió no existe.

5. **El review es el juego entero.** Generar borradores es barato. El
   valor escaso es el **juicio**. El `judge` no edita: poda.

6. **La validación es compute-bound, y vale el costo.** Una suite verde
   solo dice "no explota". La prueba de mutación (PIT) introduce
   defectos y exige que algún test falle. Es lo que demuestra que la red
   atrapa peces. Ver `docs/uncle-bob/mutation-testing.md`.

## Mapa de artefactos (quién escribe qué)

| Archivo                                                          | Lo escribe                     | Contiene                                                                               |
|------------------------------------------------------------------|--------------------------------|----------------------------------------------------------------------------------------|
| `project-spec.md`                                                | spec_partner                   | Sub-secciones por feature: propósito, contrato Java, decisiones, casos límite. Referencia a SPEC.md/docs/. |
| `features/<name>.feature`                                        | gherkin_author                 | Escenarios Gherkin `@s1..@sn` (el contrato firmado)                                   |
| `backend/src/main/java/com/myfinanceview/domain/**`              | tdd_craftsman                  | Código del dominio puro                                                                |
| `backend/src/test/java/com/myfinanceview/domain/**`              | tdd_craftsman                  | Tests JUnit 5 con tag `@sN` en `@DisplayName`                                         |
| `progress/tdd_<name>.md`                                         | tdd_craftsman                  | Bitácora de ciclos + mapa `@s → test`                                                  |
| `progress/judge_<name>.md`                                       | judge                          | Veredicto de review + checkpoints                                                      |
| `progress/mutation_<name>.md`                                    | mutation_tester                | Score de mutación PIT + mutantes sobrevivientes                                        |
| `feature_list.json`                                              | craftsman_lead / tdd_craftsman | `pending → spec_ready → in_progress → done`                                            |

**Regla anti-teléfono-descompuesto:** los subagentes escriben en disco y
devuelven una sola línea de referencia. El contenido no circula por chat.
