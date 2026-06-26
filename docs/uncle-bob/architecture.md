# Arquitectura del dominio puro — qué significa "buen trabajo"

> La autoridad de diseño del proyecto (SPEC.md, docs/backend-standards.md,
> docs/base-standards.md) define la arquitectura general y el modelo de
> datos. Este harness toca **solo** la capa más interna: el **dominio
> puro** (`backend/src/main/java/com/myfinanceview/domain/**`).

## El núcleo, en una frase

```
domain/**  ←  clases y records puros: entrada → salida, sin efectos.
               Reciben "ahora" y config como parámetros.
               No saben de Postgres, jOOQ, Spring ni reloj del sistema.
```

## Reglas de frontera (no negociables)

Estas reglas son **reglas del proyecto** (docs/base-standards.md §4–5,
docs/backend-standards.md §2). No son sugerencias.

1. **Sin IO.** Nada de `org.jooq`, `org.springframework`, `java.sql`,
   `java.io`, repositorios ni red.
2. **Sin reloj implícito.** Cero `Instant.now()`, `LocalDate.now()`,
   `OffsetDateTime.now()`, `System.currentTimeMillis()`, `new Date()`.
   Si necesitas la hora, recíbela como parámetro: un `Clock` inyectado
   o un `OffsetDateTime now`.
3. **Sin singletons mutables.** Las clases del dominio no leen ni
   escriben estado estático mutable. La config entra como argumento.
4. **Sin fallback silencioso (regla anti-adivinanza).** Una categoría
   desconocida, una tarifa no encontrada, una regla no clasificada →
   lanza una excepción de dominio tipada con mensaje útil. NUNCA un
   valor por defecto silencioso cuando hay dinero de por medio.
5. **Dinero.** `BigDecimal` siempre, `RoundingMode.HALF_EVEN`, escala 2.
   `double`/`float` en el dominio **es un bug** (regla dura del
   proyecto). Redondeo solo en los puntos definidos en
   `docs/uncle-bob/conventions.md`.
6. **Tiempo.** `OffsetDateTime` en UTC en el dominio y en la DB;
   conversión a `America/Bogota` solo en presentación (fuera del dominio).
7. **Ids.** `UUID` siempre. DTOs y objetos de valor: `record` inmutables.
8. **Errores.** Excepciones de dominio tipadas con mensaje útil. El
   controller externo (fuera del scope) las traduce a RFC 7807
   `ProblemDetail`. En el dominio no se decide el código HTTP.

## Estructura objetivo (ilustrativa, no prescriptiva)

```
backend/src/main/java/com/myfinanceview/domain/
├── billing/      (p. ej. BillingPeriod, resolución de período por día de corte)
├── category/     (reglas de categorización pura)
├── merchant/     (matching de comercios)
├── savings/      (progreso de metas de ahorro)
└── transaction/  (lógica de dominio de movimientos)
```

El espejo de tests va en
`backend/src/test/java/com/myfinanceview/domain/**`.

## Cómo el `judge` mide pureza

Tres greps mecánicos, sobre
`backend/src/main/java/com/myfinanceview/domain/`:

```bash
grep -REn "import (org\.jooq|org\.springframework|java\.sql|java\.io|.*\.db\.|.*Repository)"
# debe estar vacío

grep -REn "now\(\)|currentTimeMillis|new Date\("
# debe estar vacío

grep -REn "System\.(out|err)\.|printStackTrace"
# debe estar vacío
```

Cualquier coincidencia es rechazo automático.

## Lo que sí está permitido

- `java.math.BigDecimal`, `java.math.RoundingMode`, `java.util.*`.
- `java.time.OffsetDateTime`, `java.time.LocalDate`, `java.time.Clock`
  — siempre que `Clock` o `now` entren como parámetro, nunca llamados
  con el reloj del sistema dentro del dominio.
- `java.util.UUID`.
- Excepciones propias del dominio (que extienden una jerarquía de
  RuntimeException del proyecto).
- `new OffsetDateTime(...)` o `OffsetDateTime.parse(...)` con literal
  fijo **solo** dentro de `backend/src/test/java/` para construir
  fixtures.

## Fuera de alcance del harness

- Repositorios jOOQ (`*Repository`, `*Dao`).
- Services Spring (`@Service`, `@Component`).
- Controladores REST (`@RestController`, `@RequestMapping`).
- Migraciones DDL (carpeta `backend/database/`).
- Cualquier capa de infraestructura.

Cada uno tiene su propio plan/spec (ver SPEC.md §3 y docs/backend-standards.md).
