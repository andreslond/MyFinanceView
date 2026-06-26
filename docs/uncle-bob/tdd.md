# TDD estricto — la disciplina del `tdd_craftsman`

> "Single test followed by code (TDD)." Un test a la vez. Nunca toda la
> batería por delante.

## Las Tres Leyes del TDD

1. **No escribes código de producción** salvo para hacer pasar un test
   que está fallando.
2. **No escribes más de un test del necesario para fallar** — y que no
   compile o que el método no exista cuenta como fallar.
3. **No escribes más código de producción del necesario** para pasar el
   único test que falla.

El efecto: nunca tienes código sin un test que lo justifique, ni un test
que no esté empujando código real. El alcance no se infla.

## El ciclo

```
 ROJO            VERDE                 REFACTOR
 escribe UN  →   mínimo código    →    limpia con
 test que        para ponerlo          la barra
 falla           verde                 verde
```

- **ROJO** — el test deriva del siguiente escenario `@s` del `.feature`.
  Verifícalo fallando de verdad:
  `mvnd -q -o test -Dtest='<ClaseTest>'` (desde `backend/`).
  Un test que pasa a la primera no demuestra nada: ajústalo o sospecha
  del montaje.
- **VERDE** — la implementación **mínima**. Está permitido devolver una
  constante o hardcodear un caso si aún no hay test que lo desmienta. El
  siguiente ciclo forzará la generalización. Es deliberado.
- **REFACTOR** — solo en verde. Elimina duplicación, mejora nombres,
  parte métodos largos. Vuelve a correr los tests tras cada cambio. Si
  algo se pone rojo, no estás refactorizando: estás cambiando comportamiento.

## Granularidad: un escenario, uno o más ciclos

Cada `@s` del `.feature` se traduce en al menos un ciclo Rojo-Verde-
Refactor. Un escenario con varias aristas (p. ej. "fecha == día de corte"
y "fecha posterior") puede necesitar dos ciclos para forzar la
generalización.

## Trazabilidad obligatoria

Al cerrar, cada `@s` debe estar cubierto por al menos un test concreto.
El `tdd_craftsman` escribe el mapa en `progress/tdd_<name>.md`:

```markdown
## Trazabilidad
- @s1 (período anterior al corte)   → BillingPeriodResolverTest#shouldResolvePreviousPeriodWhenDateBeforeCutDay
- @s2 (período posterior al corte)  → BillingPeriodResolverTest#shouldResolveNextPeriodWhenDateAfterCutDay
- @s3 (categoría desconocida lanza) → CategoryResolverTest#shouldThrowWhenCategoryUnknown
```

El `judge` rechaza si algún `@s` queda sin test, y el `mutation_tester`
expone si esos tests no muerden.

## Reglas duras propias del dominio puro

- No imports de `org.jooq`, `org.springframework`, `java.sql`, `java.io`
  ni red en el dominio. Ver `docs/uncle-bob/architecture.md`.
- Sin reloj implícito: cero `Instant.now()`, `LocalDate.now()`,
  `OffsetDateTime.now()`, `System.currentTimeMillis()`, `new Date()`.
  "Ahora" entra como parámetro: un `Clock` inyectado o un
  `OffsetDateTime now`.
- Sin fallback silencioso: una categoría desconocida, una tarifa no
  encontrada o una regla no clasificada **lanzan** una excepción de
  dominio tipada con mensaje útil. El controlador externo (fuera del
  scope) la traducirá; aquí no se traga.
- Dinero como `BigDecimal` con `RoundingMode.HALF_EVEN`, escala 2.
  `double`/`float` en el dominio es un bug. Ver `docs/uncle-bob/conventions.md`.
