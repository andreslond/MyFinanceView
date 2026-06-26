# project-spec.md — Dominio puro de MyFinanceView (harness Uncle Bob)

> Este archivo es el **resultado conversacional** del `spec_partner`. NO
> duplica la autoridad de diseño: la referencia y va añadiendo decisiones
> puntuales por feature a medida que se conversan.
>
> Arranca casi vacío: cada feature SDD gana su sub-sección cuando el
> `spec_partner` la conversa con el humano, **antes** de destilar el
> Gherkin y **antes** de cualquier código.

## Punto de partida (autoridad de diseño vigente)

El diseño y las reglas del proyecto viven en:

- **`SPEC.md`** — visión, stack, decisiones clave (north star).
- **`docs/base-standards.md`** — principios transversales, DoD, reglas de
  oro (dinero `BigDecimal` HALF_EVEN escala 2, tiempo `OffsetDateTime` UTC,
  ids `UUID`, DTOs `record`, errores RFC 7807).
- **`docs/backend-standards.md`** — Java 25 / Spring Boot / jOOQ.
- **`docs/data-model.md`** — esquema `myfinance` + migraciones.
- **`plans/`** — planes por feature cuando existan.

Leer la sección relevante antes de conversar una feature. El `spec_partner`
referencia estas fuentes; no las copia.

## Alcance del harness

Solo el **núcleo funcional puro** (DDD) en
`backend/src/main/java/com/myfinanceview/domain/**`. Fuera de alcance (van
por su camino normal): controllers REST, repositorios jOOQ, migraciones SQL,
auth, ingestión por n8n/Gmail, el LLM de categorización, cron, frontend.
Esas capas **consumen** el dominio puro que aquí se talla.

## Reglas duras heredadas del diseño

1. **Núcleo puro:** cero IO (jOOQ/Spring/JDBC/red), cero reloj implícito
   (`Instant.now()`/`LocalDate.now()`/`new Date()`); el "ahora", fechas y
   config entran siempre como argumentos.
2. **Anti-adivinanza:** una categoría/tarifa/regla sin resolver **lanza** una
   excepción de dominio visible; nunca un fallback silencioso a un valor por
   defecto cuando hay dinero de por medio.
3. **Dinero `BigDecimal` HALF_EVEN escala 2.** `double`/`float` en el dominio
   es un bug. Redondeo solo en puntos explícitos.
4. **Tiempo `OffsetDateTime` UTC** en el dominio; la conversión a
   `America/Bogota` es de presentación, fuera del dominio.
5. **Tipos:** `record` inmutables; uniones cerradas como `enum`/`sealed`.

---

## Sub-secciones por feature (las rellena el `spec_partner`)

> Aún no hay features conversadas. Cuando el `craftsman_lead` lance al
> `spec_partner` sobre la primera feature `pending` de `feature_list.json`,
> su sub-sección aparece aquí con: Propósito · Comportamiento · Contrato
> (Java) · Errores · Casos límite · Decisiones (cada una con su alternativa
> descartada).

<!-- placeholder: el spec_partner añade aquí ### <feature_name> — #<id> -->
