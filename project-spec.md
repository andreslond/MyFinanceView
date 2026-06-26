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

---

### transaction_categorization_rules — #2

#### Propósito

Camino rápido y **determinista** de categorización previo al LLM: dado un movimiento
normalizado (descriptor, monto, tipo de transacción) y una lista de reglas de dominio,
asigna una categoría del catálogo de forma pura e idempotente.

Implementa la rama "fast path" del flujo de `SPEC.md §7`: cuando un merchant tiene
`confidence >= 0.85`, la categoría se asigna directamente sin llamar al LLM. Este dominio
encapsula la lógica de esa evaluación. Lo que ninguna regla resuelve — o lo que produce
ambigüedad entre categorías distintas — devuelve `NoMatch` explícito para que el LLM
decida; **nunca se adivina una categoría por defecto en silencio** (regla anti-adivinanza,
`docs/uncle-bob/architecture.md §Reglas de frontera §4`).

**Paquete destino:** `com.myfinanceview.domain.category`
(conforme a `docs/uncle-bob/architecture.md §Estructura objetivo`).

Sin IO, sin reloj, sin Spring, sin jOOQ. Los datos del movimiento y las reglas entran
íntegramente como parámetros.

---

#### Comportamiento

**Firma de entrada:** `categorize(transaction: CategorizableTransaction, rules: List<CategoryRule>) → CategoryMatch`

**Algoritmo de evaluación de una sola regla:**

Una `CategoryRule` coincide (_matches_) con un `CategorizableTransaction` si y solo si
**todos** sus predicados no-nulos/no-vacíos son verdaderos de forma simultánea (semántica
AND). Los predicados ausentes (null / colección vacía) no se evalúan: no suponen restricción.

| Predicado de la regla | Evaluación |
|---|---|
| `merchantPattern` (no null) | `descriptor.toLowerCase().contains(merchantPattern.toLowerCase())` |
| `minAmount` (no null) | `amount.compareTo(minAmount) >= 0` |
| `maxAmount` (no null) | `amount.compareTo(maxAmount) <= 0` |
| `transactionKinds` (no vacío) | `transactionKinds.contains(transaction.type())` |

**Determinación del resultado** a partir del conjunto de reglas que coinciden:

| Reglas que coinciden | Resultado |
|---|---|
| Ninguna | `NoMatch` |
| Una o más, **todas** apuntan a la misma categoría (mismo `CategoryRef.id()`) | `Matched(categoryRef)` |
| Dos o más, apuntan a **categorías distintas** | `NoMatch` (ambigüedad — anti-adivinanza) |

La coincidencia en categoría se determina por `UUID id`, no por `name`. Si dos reglas
apuntan al mismo UUID, se consideran coincidentes en categoría aunque los objetos
`CategoryRef` difieran en otro campo.

**Sin estado mutable, sin IO, sin reloj.** El resultado sobre el mismo par
`(transaction, rules)` es siempre idéntico: determinista e idempotente.

---

#### Contrato Java

```java
// ── Enum de dominio: tipos de transacción ──────────────────────────────────────
// Espejo puro de transaction_type (DB enum). La capa de servicio convierte del
// tipo jOOQ generado a este enum; el dominio no importa nada de jOOQ.
public enum TransactionKind {
    CREDIT_CARD_PURCHASE,
    DEBIT_PURCHASE,
    CREDIT_CARD_PAYMENT,
    INCOMING_TRANSFER,
    OUTGOING_TRANSFER,
    INCOMING_PAYMENT
}

// ── Input mínimo del movimiento para categorización ───────────────────────────
// El caller normaliza los campos del movimiento antes de invocar el dominio.
// La conversión de occurred_at (OffsetDateTime UTC) a localDate en
// America/Bogota, y de transaction_type (jOOQ) a TransactionKind, son
// responsabilidad de la capa de servicio.
public record CategorizableTransaction(
    String descriptor,        // texto crudo del banco; no null, no blank
    BigDecimal amount,        // monto ≥ 0, BigDecimal escala 2; no null
    TransactionKind type,     // tipo de la transacción; no null
    String currency           // ISO 4217, ej. "COP"; no null
) {}

// ── Referencia estable a una categoría del catálogo ───────────────────────────
// id  : categories.id (UUID, clave primaria, estable)
// name: categories.name (clave inglesa, ej. "Dining Out"; no el display_name ES)
// El nombre en español (display_name) es responsabilidad de la capa de presentación.
public record CategoryRef(UUID id, String name) {}

// ── Regla de categorización determinista ──────────────────────────────────────
// Predicados null/vacío = sin restricción en esa dimensión.
// Al menos un predicado debe ser no-nulo/no-vacío; merchantPattern != null implica no-blank.
public record CategoryRule(
    UUID id,                               // ID estable de la regla (trazabilidad)
    String merchantPattern,               // null → sin restricción de descriptor; si no null, debe ser no-blank
    BigDecimal minAmount,                  // null → sin cota inferior de monto
    BigDecimal maxAmount,                  // null → sin cota superior de monto
    java.util.Set<TransactionKind> transactionKinds, // vacío → cualquier tipo
    CategoryRef category                   // no null; categoría a asignar si coincide
) {
    // Invariante de construcción (a verificar por el tdd_craftsman):
    // – Al menos un predicado debe ser no-nulo/no-blank/no-vacío.
    // – Si merchantPattern != null, debe ser no-blank.
    // Estas condiciones se lanzan como InvalidRuleException en tiempo de evaluación.
}

// ── Resultado sellado — type-safe, sin null oculto ─────────────────────────────
public sealed interface CategoryMatch permits Matched, NoMatch {}

public record Matched(CategoryRef category) implements CategoryMatch {}

public record NoMatch() implements CategoryMatch {}

// ── Clase resolutora — sin estado, método estático puro ───────────────────────
public final class TransactionCategorizer {

    private TransactionCategorizer() {}

    /**
     * Applies {@code rules} to {@code transaction} and returns the deterministic
     * category match.
     *
     * <ul>
     *   <li>Empty rules or no rule matches → {@link NoMatch}</li>
     *   <li>One or more rules match, all pointing to the same category → {@link Matched}</li>
     *   <li>Two or more rules match pointing to different categories → {@link NoMatch}
     *       (ambiguity — anti-guess principle)</li>
     * </ul>
     *
     * @param transaction normalised transaction data; not null, descriptor not blank,
     *                    amount ≥ 0
     * @param rules       ordered list of categorisation rules; may be empty (→ NoMatch)
     * @throws InvalidTransactionDescriptorException if descriptor is null or blank
     * @throws InvalidRuleException if a rule has no predicates, or merchantPattern is non-null but blank
     */
    public static CategoryMatch categorize(
        CategorizableTransaction transaction,
        java.util.List<CategoryRule> rules
    ) {
        // implementation by tdd_craftsman
    }
}
```

**Parámetros:**

| Parámetro | Tipo | Restricciones |
|---|---|---|
| `transaction` | `CategorizableTransaction` | no null; `descriptor` no blank; `amount >= 0` |
| `rules` | `List<CategoryRule>` | no null; puede ser vacía → `NoMatch`; no puede contener elementos null |

**Retorno:** `CategoryMatch` — nunca null; siempre `Matched` o `NoMatch`.

**Nombre del método:** `categorize` — verbo directo, sin redundancia con el nombre de la
clase (`TransactionCategorizer.categorize`). Coincide con la acceptance criteria de
`feature_list.json` (#2): `categorize(transaction, rules)`.

---

#### Errores

| Condición | Excepción | Mensaje mínimo |
|---|---|---|
| `transaction == null` | `NullPointerException` (JDK) | n/a — la capa llamante garantiza no-null |
| `transaction.descriptor()` null o blank | `InvalidTransactionDescriptorException extends DomainException` | `"Transaction descriptor must not be blank."` |
| `transaction.amount()` null | `NullPointerException` (JDK) | n/a |
| `transaction.amount()` negativo | `InvalidTransactionAmountException extends DomainException` | `"Transaction amount must be >= 0, got: N."` |
| `rules == null` | `NullPointerException` (JDK) | n/a |
| `rules` contiene elemento null | `NullPointerException` (JDK) | n/a — contrato por defecto de List |
| Regla sin ningún predicado (catch-all total) | `InvalidRuleException extends DomainException` | `"Rule <id> has no predicates — at least one predicate is required."` |
| `minAmount > maxAmount` en una regla | `InvalidRuleException extends DomainException` | `"Rule <id>: minAmount must be <= maxAmount."` |

`DomainException` ya existe en `com.myfinanceview.domain.DomainException`.
Los nombres de excepción siguen el patrón `Invalid<Contexto>Exception` de
`docs/uncle-bob/conventions.md §Errores`.

---

#### Casos límite

| Caso | Resultado esperado |
|---|---|
| Lista de reglas vacía | `NoMatch` |
| Ninguna regla coincide con el movimiento | `NoMatch` |
| Una sola regla coincide | `Matched(categoryRef)` |
| Varias reglas coinciden, todas apuntan a la misma categoría (mismo UUID) | `Matched(categoryRef)` |
| Varias reglas coinciden con categorías distintas (distintos UUIDs) | `NoMatch` (ambigüedad) |
| `descriptor` contiene espacios al inicio/final | Se evalúa el valor raw (el caller decide si normalizar antes); `InvalidTransactionDescriptorException` si solo whitespace |
| `amount == 0` | Válido (ej. devolución total); no lanza excepción |
| `merchantPattern` coincide como substring case-insensitive | Solo si el descriptor contiene el patrón (evaluación: `descriptor.toLowerCase().contains(pattern.toLowerCase())`) |
| `minAmount == maxAmount` (restricción puntual) | Válido; solo coincide con monto exacto |
| Regla con solo `minAmount` (sin `maxAmount`) | Válido; coincide con todo monto >= minAmount |
| `transactionKinds` vacío en la regla | Sin restricción de tipo → no filtra por tipo |
| `transactionKinds` null en la regla | Tratado igual que vacío (sin restricción de tipo) |
| Mismo movimiento evaluado dos veces con las mismas reglas | Resultado idéntico (idempotencia) |

---

#### Decisiones

##### CLOSED — Tipo de retorno: `sealed interface CategoryMatch permits Matched, NoMatch`

Establecido por la acceptance criteria: `categorize(transaction, rules) -> CategoryMatch
(categoría conocida o NO_MATCH explícito)`. La forma `sealed interface` con `record Matched`
y `record NoMatch` es type-safe, fuerza al consumidor a tratar ambos casos en un `switch`,
y sigue la convención de `docs/uncle-bob/conventions.md §Java` ("Usa `sealed interface /
enum` para uniones cerradas"). No hay null oculto ni `Optional` anidado.

**Alternativa descartada:** `record CategoryMatch(@Nullable CategoryRef category)` — un campo
nullable rompe el type-safety y permite que el llamante ignore el caso NoMatch sin
compilar en error. Violación del anti-adivinanza.

---

##### CLOSED — Anti-adivinanza: ausencia de regla devuelve `NoMatch`, sin fallback

Ninguna categoría "Miscellaneous" o "Sin categoría" se asigna silenciosamente cuando
ninguna regla coincide. El dominio devuelve `NoMatch` y la capa de servicio decide el
siguiente paso (llamar al LLM, encolar para revisión, etc.). Establecido explícitamente
por la acceptance criteria.

**Alternativa descartada:** devolver una categoría "Other" por defecto — oculta errores de
configuración (reglas mal formuladas), viola la regla anti-adivinanza del proyecto
(`docs/uncle-bob/architecture.md §4`) y produce datos incorrectos sin trazabilidad.

---

##### CLOSED — Categorías referenciadas por `CategoryRef(UUID id, String name)` del catálogo

El `name` de la referencia es `categories.name` (etiqueta inglesa del catálogo: "Dining
Out", "Groceries", etc.), **no** un string inventado ni `display_name` en español. El UUID
es `categories.id` (clave primaria, FK estable). Ambos campos provienen de la regla
que ya los tenía resueltos desde la DB (vía la capa de servicio); el dominio no los
inventa ni los busca.

**Razón del campo `name`:** la acceptance criteria fija explícitamente que "las categorías
referencian el catálogo del proyecto (name en inglés)". Incluir también el UUID permite
a la capa de servicio escribir directamente `category_id` a la DB sin una segunda consulta.
El `display_name` en español es responsabilidad de la capa de presentación, fuera del dominio.

**Alternativa descartada:** solo UUID — opaco en el dominio, dificulta la trazabilidad en
tests y logs. Alternativa `solo name` — requiere lookup adicional de UUID al persistir.

---

##### CLOSED — Sin IO, sin reloj, sin estado mutable

`TransactionCategorizer` es `final` con constructor privado y método `public static`.
Idéntica estructura a `BillingPeriodResolver` (#1), que ya es la referencia de estilo del
proyecto. Sin `@Service`, sin `@Component`, sin `Instant.now()`.

**Alternativa descartada:** clase con estado (lista de reglas inyectada en constructor) —
añadiría statefulness innecesario; cada llamada recibe sus propias reglas por parámetro,
lo que es más flexible y más fácil de testear.

---

##### CLOSED — Dinero: `BigDecimal` con `RoundingMode.HALF_EVEN` en comparaciones

Los predicados de monto usan `compareTo`, **nunca** `equals` (`BigDecimal.equals` incluye
la escala en la comparación). Escala 2 heredada de la DB (`numeric(18,2)`). Regla dura del
proyecto (`docs/base-standards.md §4`, `docs/uncle-bob/conventions.md §Dinero`).

---

##### CLOSED — `TransactionKind`: enum de dominio puro, desacoplado de jOOQ

El dominio no puede importar el tipo jOOQ generado (`org.jooq.*` prohibido en `domain/**`
por `docs/uncle-bob/architecture.md §Reglas de frontera §1`). Se define `enum
TransactionKind` en `domain/category/` con los mismos literales que el DB enum
`transaction_type` (V001). La capa de servicio mapea jOOQ → dominio antes de llamar.
Cuando feature #3 (`merchant_matching`) necesite el mismo enum, el `tdd_craftsman` de esa
feature puede elevarlo a un paquete compartido (decisión YAGNI-safe: no pre-anticipar hoy).

**Alternativa descartada:** `String type` — pierde seguridad de tipos en tiempo de
compilación; permite que valores inválidos entren al dominio sin ser detectados.

---

##### CLOSED — Semántica de `merchantPattern`: `contains` case-insensitive

El patrón coincide si y solo si `descriptor.toLowerCase().contains(merchantPattern.toLowerCase())`.
La evaluación es puramente booleana: no hay wildcards, no hay compilación de expresiones
regulares, no puede lanzar `PatternSyntaxException`.

**Razón:** es el mínimo suficiente para los descriptores reales de Davivienda/Bancolombia
(BOLD, RAPPI, DIDI, UBER). No requiere parser, es trivial de testear, y se demuestra
determinista sin condiciones especiales. Un patrón tipo `"RAPPI"` ya coincide con el
descriptor real `"RAPPI COLOMBIA S.A.S"` sin necesidad de wildcards. Si en el futuro se
necesita glob o regex, se extiende el predicado sin cambiar la firma del método.

**Alternativa descartada:** glob (`*`, `?`) y regex — más expresivos pero requieren un
parser o `Pattern.compile`, lo que introduce errores en tiempo de ejecución con patrones
malformados y complejidad de validación extra; descartados por YAGNI.

---

##### CLOSED — Precedencia cuando varias reglas coinciden: ambigüedad → `NoMatch`

Cuando dos o más reglas coinciden con el mismo movimiento y apuntan a **categorías
distintas** (distintos `CategoryRef.id()` UUID), el resultado es `NoMatch` explícito.
Para que el resultado sea `Matched`, todas las reglas que coinciden deben apuntar al mismo
UUID de categoría. No existe first-match-wins ni regla catch-all implícita.

**Razón:** la acceptance criteria distingue explícitamente "Ambigüedad → NoMatch" de
"ausencia de regla → NoMatch": son dos causas distintas del mismo resultado. Un conflicto
de reglas es información de configuración incorrecta o de genuina ambigüedad de negocio;
rutarlo al LLM (la siguiente etapa del pipeline de `SPEC.md §7`) es la respuesta correcta.
El principio anti-adivinanza (`docs/uncle-bob/architecture.md §Reglas de frontera §4`)
prohíbe cualquier fallback silencioso cuando el resultado no está unívocamente determinado.
Consecuencia directa: una regla sin predicados (catch-all total) es inválida (ver decisión
sobre OQ-4 abajo).

**Alternativa descartada:** first-match wins — el orden de las reglas se convierte en un
parámetro implícito de la lógica; un cambio accidental de orden produce categorías
diferentes sin que el dominio lo detecte. Permite defaults silenciosos, violando
anti-adivinanza.

---

##### CLOSED — Campo `currency` en `CategorizableTransaction`: incluido

El record `CategorizableTransaction` incluye `String currency` (ISO 4217, ej. `"COP"`).

**Razón:** el campo `currency` ya existe en `transactions.currency` (DB); el caller lo pasa
sin costo extra. Una regla de monto sin moneda podría comparar incorrectamente $20,000 COP
con $10 USD; incluir la moneda desde el principio previene esa clase de bug sin ningún
overhead de implementación real. Las reglas del dominio pueden ignorar el campo si el
predicado no lo evalúa.

**Alternativa descartada:** omitir `currency` — el proyecto opera principalmente en COP
hoy, pero la simplificación crea un punto de fallo silencioso para transacciones en USD
(tarjetas internacionales) y obligaría a una firma breaking-change en el futuro.

---

##### CLOSED — Regla sin ningún predicado: inválida → `InvalidRuleException`

Una `CategoryRule` en la que todos los predicados son nulos/vacíos
(`merchantPattern = null`, `minAmount = null`, `maxAmount = null`,
`transactionKinds = vacío/null`) es rechazada por el dominio con
`InvalidRuleException extends DomainException` en el momento de la evaluación.

**Razón:** una regla sin predicados coincide con cualquier movimiento; es un catch-all
implícito disfrazado de regla de negocio. Bajo la decisión de ambigüedad → NoMatch (OQ-2),
una catch-all al final de la lista siempre crearía conflicto con cualquier regla específica
que también coincida, haciendo el sistema inoperable. El dominio detecta el error en
validación temprana y lanza una excepción visible, conforme al principio anti-adivinanza.

**Alternativa descartada:** tratar la catch-all como válida — permite que errores de
configuración pasen silenciosamente al dominio; incompatible con la decisión OQ-2 (ambigüedad
→ NoMatch).

---

##### CLOSED — `merchantPattern` vacío o blank: inválido → `InvalidRuleException`

Si `merchantPattern != null`, debe ser una cadena no-blank. Un patrón `""` (vacío) o solo
espacios en blanco siempre retorna `true` en `contains`, lo que lo convierte en catch-all
de descriptor. El dominio lanza `InvalidRuleException` con mensaje:
`"Rule <id>: merchantPattern must not be blank when non-null."`.

**Razón:** `merchantPattern = ""` es funcionalmente equivalente a ausencia del predicado de
descriptor, pero enmascarado — viola el mismo principio que OQ-4 (anti-adivinanza). Fallar
rápido con un mensaje claro es más honesto que tratar `""` como `null` en silencio.

**Alternativa descartada:** tratar `""` como `null` (sin restricción) — comportamiento
liberal que oculta errores de datos de la DB (un patrón vacío que llegó de un INSERT
incorrecto); introduce divergencia entre `null` y `""` semánticamente equivalentes pero
tratados de forma diferente en validación posterior.
