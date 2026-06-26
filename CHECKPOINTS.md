# CHECKPOINTS — Evaluación del estado final (harness Uncle Bob)

> En sistemas multi-agente no se evalúa el camino, se evalúa el destino.
> Estos son los checkpoints objetivos que un juez (humano o IA) puede usar
> para decidir si el trabajo de una feature está sano.
>
> Scope: `backend/src/main/java/com/myfinanceview/domain/**` y
> `backend/src/test/java/com/myfinanceview/domain/**`.

## C1 — El arnés está completo

- [ ] Existen los archivos base: `AGENTS.md`, `init.ps1`,
      `feature_list.json`, `progress/current.md`, `progress/history.md`,
      `project-spec.md`.
- [ ] Existen los docs guía: `docs/uncle-bob/{workflow,tdd,gherkin,
      mutation-testing,architecture,conventions,verification}.md`.
- [ ] Existen los 6 agentes en `.claude/agents/`.
- [ ] `backend/pom.xml` declara el plugin `org.pitest:pitest-maven`.
- [ ] `init.ps1` termina sin `[FAIL]`.

## C2 — El estado es coherente

- [ ] Como mucho una feature en `in_progress` en `feature_list.json`.
- [ ] Toda feature `done` tiene tests asociados que pasan.
- [ ] `progress/current.md` está vacío (plantilla) o describe la sesión
      activa.

## C3 — El código respeta la arquitectura (dominio puro)

- [ ] `backend/src/main/java/com/myfinanceview/domain/**` no importa jOOQ,
      Spring, JDBC, repositorios ni IO
      (`grep -REn "import (org\.jooq|org\.springframework|java\.sql|java\.io|.*\.db\.|.*Repository)"` vacío).
- [ ] Cero relojes implícitos
      (`grep -REn "now\(\)|currentTimeMillis|new Date\("` vacío); el "ahora"
      entra como parámetro.
- [ ] Sin `System.out`/`System.err`/`printStackTrace` ni TODOs sin contexto.
- [ ] Dinero en `BigDecimal` HALF_EVEN escala 2; nada de `double`/`float`
      en cálculo monetario. Tiempo en `OffsetDateTime` UTC. Ids `UUID`.

## C4 — La verificación es real

- [ ] `backend/src/test/java/com/myfinanceview/domain/**` tiene al menos un
      test por clase nueva.
- [ ] Los tests del dominio son puros (sin Testcontainers, sin DB, sin red).
- [ ] `mvnd -q test -Dtest='com.myfinanceview.domain.**'` muestra > 0 tests
      y todos verdes.

## C5 — La sesión se cerró bien

- [ ] No hay archivos sin trackear sospechosos (`*.tmp`, reportes de PIT
      fuera del `.gitignore`).
- [ ] `progress/history.md` tiene una entrada por la última sesión.
- [ ] La última feature trabajada está reflejada en su estado correcto.

## C6 — Contrato Gherkin (BDD)

- [ ] Toda feature con `"sdd": true` en estado `spec_ready`, `in_progress`
      o `done` tiene su `features/<name>.feature` y una sub-sección en
      `project-spec.md` (o referencia explícita a la autoridad de diseño).
- [ ] El `.feature` usa Gherkin con escenarios tagueados `@s1`, `@s2`, …
      y cada `Then` afirma algo medible (ver `docs/uncle-bob/gherkin.md`).
- [ ] Cada escenario `@s` está cubierto por al menos un test JUnit cuyo
      `@DisplayName` lleva el tag (mapa `@s → test` en
      `progress/tdd_<name>.md`).
- [ ] No hay código de producción que ningún test rojo haya pedido
      (disciplina TDD, ver `docs/uncle-bob/tdd.md`).

## C7 — Prueba de mutación

- [ ] La feature `done` superó la prueba de mutación
      (`mvnd org.pitest:pitest-maven:mutationCoverage` acotado a las clases
      tocadas) con la puntuación por encima del umbral de
      `docs/uncle-bob/mutation-testing.md` (100% sobre líneas nuevas o
      tocadas).
- [ ] Cualquier mutante sobreviviente queda documentado en
      `progress/mutation_<name>.md` (matado con un test nuevo, o justificado
      como equivalente).

---

**Cómo usar este archivo:** el agente `judge` recorre C1-C6 y el
`mutation_tester` valida C7. Se rechaza el cierre de feature si quedan
boxes vacíos.
