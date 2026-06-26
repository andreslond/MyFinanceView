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

### billing_period_resolution — #1

#### Propósito

Función pura que, dado un día de corte (`cutDay`) y la fecha de un movimiento (`transactionDate`),
resuelve el período de facturación `[start, end]` al que pertenece dicho movimiento.
Es el equivalente de dominio al `get_billing_period` SQL pendiente
(TASK-DB-03, `docs/data-model.md §3`).
No conoce la tarjeta concreta ni la DB: recibe únicamente el `cutDay` y la fecha como parámetros.

---

#### Comportamiento

Dado `cutDay ∈ [1..31]` y `transactionDate: LocalDate`:

**1. Asignación de período** (regla de bordes):

- `transactionDate.getDayOfMonth() ≤ cutDay` → movimiento pertenece al **período actual**
  (cierra en el `cutDay` efectivo del mismo mes que `transactionDate`).
- `transactionDate.getDayOfMonth() > cutDay` → movimiento pertenece al **período siguiente**
  (cierra en el `cutDay` efectivo del mes siguiente a `transactionDate`).

La comparación usa el `cutDay` literal (no el efectivo), porque ningún día del mes puede
superar el número de días del mes; cuando `cutDay > daysInMonth`, todos los días del mes
resultan ≤ `cutDay`, lo que produce correctamente que el período entero cierre en el último
día de ese mes (ver Convención de clamping abajo).

**2. Convención de clamping para meses cortos** (`effectiveCutDay`):

```
effectiveCutDay(month, year) = min(cutDay, lastDayOfMonth(month, year))
```

Ejemplo: `cutDay=31`, febrero 2026 (28 días) → `effectiveCutDay = 28`.
Esto es documentado y determinista, no es un fallback silencioso.

**3. Cálculo de `[start, end]`** — intervalo cerrado, ambos días inclusivos:

Sea `endMonth/endYear` el mes del corte de cierre (según regla 1).

```
end   = LocalDate.of(endYear,  endMonth,  effectiveCutDay(endMonth,  endYear))
start = LocalDate.of(prevYear, prevMonth, effectiveCutDay(prevMonth, prevYear)).plusDays(1)
```

donde `prevMonth/prevYear` es el mes anterior a `endMonth/endYear`.

**4. Sin IO, sin reloj:**

La función no llama a `LocalDate.now()` ni al reloj del sistema.
`transactionDate` entra siempre como parámetro. La conversión de `occurred_at`
(`OffsetDateTime` UTC) a `LocalDate` en zona `America/Bogota` es responsabilidad de la
capa llamante (servicio / repositorio), no del dominio puro.
Ver `SPEC.md §10` y `docs/base-standards.md §4` (Time).

**5. Sin conocimiento de la tarjeta:**

La función recibe solo `cutDay` (un `int`) y `transactionDate`, no una referencia a
`Account` ni acceso a la DB. La capa de servicio obtiene `cut_day` de la cuenta y lo
pasa como parámetro.

---

#### Contrato (Java)

**Paquete destino:** `com.myfinanceview.domain.billing`
(refleja la estructura ilustrativa de `docs/uncle-bob/architecture.md §Estructura objetivo`
y el paquete ya nombrado en `docs/backend-standards.md §2`).

```java
// Record de valor — inmutable, resultado computado (sin metadatos de entrada)
public record BillingPeriod(LocalDate start, LocalDate end) {}

// Excepción de dominio — unchecked, extiende DomainException del proyecto
public final class InvalidCutDayException extends DomainException {
    public InvalidCutDayException(int cutDay) {
        super("cutDay " + cutDay + " is out of valid range [1..31]. "
              + "Provide a value between 1 and 31 inclusive.");
    }
}

// Clase resolutora — sin estado, método estático puro (sin Spring en el dominio)
public final class BillingPeriodResolver {

    private BillingPeriodResolver() {}

    /**
     * Resolves the billing period [start, end] (both inclusive) that contains
     * {@code transactionDate} for a card with the given {@code cutDay}.
     *
     * @param cutDay         day of the billing cut, 1–31 inclusive
     * @param transactionDate calendar date of the transaction (caller is responsible
     *                        for converting occurred_at OffsetDateTime to LocalDate
     *                        in America/Bogota before calling this method)
     * @throws InvalidCutDayException if cutDay < 1 or cutDay > 31
     */
    public static BillingPeriod resolve(int cutDay, LocalDate transactionDate) {
        // implementation by tdd_craftsman
    }
}
```

**Parámetros:**

| Parámetro | Tipo | Restricciones |
|---|---|---|
| `cutDay` | `int` | [1..31]; fuera → `InvalidCutDayException` |
| `transactionDate` | `LocalDate` | no null; conversión de zona horaria es responsabilidad del llamante |

**Retorno:** `BillingPeriod(LocalDate start, LocalDate end)` — intervalo cerrado [start, end]; ambos días inclusivos.

**Nombre del método:** El acceptance criteria de `feature_list.json` usa `resolveBillingPeriod`;
`docs/uncle-bob/conventions.md §Nombres` muestra el ejemplo más corto `resolvePeriod`.
Se usa `resolve` para evitar redundancia con el nombre de la clase (`BillingPeriodResolver.resolve`).
Si el `craftsman_lead` prefiere alinearse literalmente con el acceptance criteria, cambiar a
`resolveBillingPeriod`.

---

#### Errores

| Condición | Excepción | Mensaje (contenido mínimo) |
|---|---|---|
| `cutDay < 1` o `cutDay > 31` | `InvalidCutDayException` | `"cutDay N is out of valid range [1..31]."` |
| `transactionDate == null` | `NullPointerException` (estándar JDK) | n/a — la capa llamante garantiza no-null |

No hay otros errores de dominio: la convención de clamping maneja `cutDay > daysInMonth`
sin lanzar excepción (ver §Decisiones [C]).

**Relación con restricción de DB:** V006 (TASK-DB-03) restringe `cut_day BETWEEN 1 AND 28`
a nivel de DB para máxima portabilidad de corte. La función de dominio acepta [1..31] porque
el dominio es independiente de las restricciones de la DB; los valores 29–31 quedan cubiertos
por la convención de clamping.

---

#### Casos límite

| Caso | cutDay | transactionDate | Período esperado |
|---|---|---|---|
| Fecha = día de corte (borde inclusivo) | 15 | 2026-05-15 | [2026-04-16, 2026-05-15] |
| Fecha = día de corte + 1 (pasa al siguiente) | 15 | 2026-05-16 | [2026-05-16, 2026-06-15] |
| Mes corto, fecha antes del corte efectivo | 31 | 2026-02-15 | [2026-02-01, 2026-02-28] |
| Mes corto, fecha = último día del mes | 31 | 2026-02-28 | [2026-02-01, 2026-02-28] |
| Mes siguiente también tiene clamp (Apr = 30) | 31 | 2026-04-05 | [2026-04-01, 2026-04-30] |
| Corte 29, febrero normal | 29 | 2026-02-05 | [2026-01-30, 2026-02-28] |
| Año nuevo (cruza diciembre → enero) | 15 | 2026-01-05 | [2025-12-16, 2026-01-15] |
| Transición de año en el inicio del período | 31 | 2026-01-05 | [2026-01-01, 2026-01-31] |
| cutDay = 0 → excepción | 0 | cualquiera | `InvalidCutDayException` |
| cutDay = 32 → excepción | 32 | cualquiera | `InvalidCutDayException` |
| Año bisiesto, cutDay=29 | 29 | 2024-02-29 | [2024-01-30, 2024-02-29] |
| Año no bisiesto, cutDay=29, Feb | 29 | 2025-02-15 | [2025-01-30, 2025-02-28] |

---

#### Decisiones

##### CLOSED — Tipo de `transactionDate` y de los campos `start`/`end`: `LocalDate`

`LocalDate` para todos (parámetro y campos del record).

**Razón:** `SPEC.md §10` establece explícitamente "Fechas: `OffsetDateTime` para timestamps,
`LocalDate` para fechas de corte". `docs/base-standards.md §4` refuerza: "Use `LocalDate`
for calendar dates (e.g. cut day)". La función SQL equivalente `get_billing_period`
(`docs/data-model.md §3`, V006) también devuelve `date` (tipo PostgreSQL → `LocalDate` en Java).
Un período de facturación es un intervalo calendárico (día a día), no un intervalo de instantes.

La conversión de `occurred_at` (`OffsetDateTime` UTC) a `LocalDate` en zona `America/Bogota`
es responsabilidad de la capa llamante, no del dominio.

**Alternativa descartada:** `OffsetDateTime` UTC para start/end — añadiría complejidad
innecesaria a lógica puramente calendárica y violaría el principio de que la conversión
de zona horaria pertenece a la presentación.

---

##### CLOSED — Comparación directa `dayOfMonth(transactionDate) ≤ cutDay`

La comparación usa el `cutDay` literal (no el `effectiveCutDay` del mes de `transactionDate`).

**Razón:** cuando `cutDay > daysInMonth`, todos los días del mes son ≤ `cutDay`, por lo que
la comparación produce el resultado correcto sin necesidad de clampar primero. El clamping
solo aplica al calcular las fechas concretas `start`/`end`, no en la comparación de asignación.

**Alternativa descartada:** comparar `dayOfMonth(transactionDate)` vs `effectiveCutDay(cutDay,
transactionDate.getMonth())` — semánticamente equivalente pero introduce una indirección
innecesaria en la comparación central.

---

##### CLOSED — Rango de validación del dominio: `cutDay ∈ [1..31]`

**Razón:** fijado por la acceptance criteria de `feature_list.json` (#1):
"cutDay fuera de rango [1..31] lanza excepción de dominio visible (anti-adivinanza)".
El dominio es independiente de la restricción de DB (`BETWEEN 1 AND 28`); la diferencia
entre 28 y 31 es manejada por la convención de clamping documentada.

**Alternativa descartada:** validar [1..28] — acoplaría el dominio a un detalle de la DB,
violando la pureza del núcleo.

---

##### CLOSED — Campos de `BillingPeriod`: solo `start` y `end`

`record BillingPeriod(LocalDate start, LocalDate end)` — dos campos, sin `cutDay`.

**Razón:** el record de dominio expresa el resultado computado; los metadatos de configuración
(`cutDay`) son input, no output. El consumidor ya dispone del `cutDay` que pasó como parámetro.
Si la capa REST necesita exponer el `cutDay` usado, lo añade en su propio DTO de respuesta.

**Alternativa descartada:** `record BillingPeriod(LocalDate start, LocalDate end, int cutDay)` —
incluir el input en el output es redundante y mezcla responsabilidades; el record es más
complejo sin aportar valor al dominio puro.

---

##### CLOSED — Intervalo cerrado `[start, end]`, ambos días inclusivos

`end = effectiveCutDay(endMonth, endYear)` — inclusivo.
`start = effectiveCutDay(prevMonth, prevYear) + 1 día` — inclusivo.

Ejemplo canónico: `cutDay=15`, `transactionDate=2026-05-20` → `period=[2026-05-16, 2026-06-15]`.

**Razón:** el lenguaje de negocio habla de "el corte es el día 15"; tanto `start` como `end`
son días del calendario, no marcadores de límite de rango abierto. La acceptance criteria
"fecha ≤ cutDay → período actual" implica que `end` es inclusivo.

**Alternativa descartada:** intervalo medio-abierto `[start, end)` donde `end = effectiveCutDay + 1 día` —
más natural para `ChronoUnit.DAYS.between(start, end)`, pero viola el lenguaje de negocio
y dificulta la lectura del contrato en contexto de facturación.

---

##### CLOSED — Clamping cuando `cutDay > daysInMonth`: `effectiveCutDay = min(cutDay, lastDayOfMonth)`

```
effectiveCutDay(month, year) = min(cutDay, lastDayOfMonth(month, year))
```

Ejemplo: `cutDay=31`, febrero 2026 (28 días) → `effectiveCutDay=28`, `end=Feb-28`, `start=Feb-01`.
El clamp aplica tanto al borde superior (`end`) como al inferior derivado del corte anterior.

**Razón:** tarjetas reales tienen cortes 29/30/31 que no pueden rechazarse en dominio.
El clamping es documentado y determinista, no un fallback silencioso: el spec lo nombra
explícitamente y el código lleva comentario de "por qué" (conforme a `docs/uncle-bob/conventions.md §Comentarios`).

**Alternativa descartada:** lanzar excepción cuando `cutDay > daysInMonth` del mes de referencia —
haría inusable cualquier cuenta con corte 29–31, acoplando innecesariamente el dominio puro
al calendario del mes en curso en lugar de tratar el clamping como regla calendárica general.

---

##### CLOSED — Nombre de la excepción de dominio: `InvalidCutDayException extends DomainException`

Un nombre por tipo de error. Sigue el patrón `UnknownCategoryException` de
`docs/uncle-bob/conventions.md §Errores`. Claro, trazable, fácil de capturar en el
`@ControllerAdvice`. Mensaje mínimo: `"cutDay N is out of valid range [1..31]."`.

Si `DomainException` no existe aún en el dominio, el `tdd_craftsman` lo crea cuando el
primer test lo pida — el `spec_partner` no lo pre-crea.

**Alternativa descartada:** `InvalidBillingArgumentException extends DomainException` —
nombre genérico prematuro (YAGNI); cubriría futuros errores inexistentes y dificulta
la captura específica en el advice del controller.

---

##### CLOSED — Estructura de `BillingPeriodResolver`: método estático en clase `final`

`public final class BillingPeriodResolver` con constructor privado y
`public static BillingPeriod resolve(int cutDay, LocalDate transactionDate)`.

**Razón:** la ausencia de estado es explícita por inspección. Sin wiring de Spring, sin
construcción, sin IO. Coherente con `docs/uncle-bob/architecture.md §Reglas de frontera`
("Sin singletons mutables", "Sin IO", "Sin Spring en el dominio"). Si la capa de servicio
necesita inyectar la lógica como `@Bean`, envuelve la clase estática sin modificar el dominio.

**Alternativa descartada:** método de instancia — permitiría inyección Spring, pero el dominio
puro no debe depender del framework; el mocking de un método puro no aporta valor de test
y el proyecto rechaza mocks innecesarios (`docs/base-standards.md §5`).
