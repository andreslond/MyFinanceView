# Convenciones — estilo, nombres, errores, dinero

> El estilo base sigue docs/backend-standards.md (Java 25, jOOQ, Spring Boot 3.4+).
> Este doc añade reglas específicas del dominio puro.

## Java

- **Return types explícitos** en cada método público. Sin `var` en
  firmas de método.
- **`record`** para objetos de valor y DTOs (inmutables por defecto).
- **`sealed interface` / `enum`** para uniones cerradas donde sea natural:
  ```java
  public sealed interface TransactionResult
      permits MatchedTransaction, UnmatchedTransaction, RejectedTransaction {}

  public enum PeriodStatus { OPEN, CLOSED, PROJECTED }
  ```
- Importa con paths de paquete completo. Sin wildcards en imports de
  dominio (para que el judge vea exactamente qué entra).

## Nombres

| Constructo            | Convención          | Ejemplo                                      |
|-----------------------|---------------------|----------------------------------------------|
| Clases / interfaces   | PascalCase          | `BillingPeriodResolver`, `CategoryRule`      |
| Records de valor      | PascalCase          | `BillingPeriod`, `TransactionAmount`         |
| Métodos               | camelCase verbal    | `resolvePeriod`, `matchMerchant`             |
| Helpers privados      | camelCase, `private`| `buildPeriodBounds`                          |
| Constantes            | SCREAMING_SNAKE     | `MAX_SAVINGS_GOAL_MONTHS`                    |
| Clases de test        | `<Clase>Test`       | `BillingPeriodResolverTest`                  |
| Métodos de test       | `should{Result}When{Condition}` | `shouldThrowWhenCategoryUnknown` |
| `@DisplayName`        | empieza con el tag  | `"@s3 — shouldThrowWhenCategoryUnknown"`     |

## Errores

Lanza excepciones de dominio tipadas. Nada de tragarlas o silenciarlas.

```java
public final class UnknownCategoryException extends DomainException {
    public UnknownCategoryException(String categoryKey) {
        super("Unknown category: " + categoryKey
              + " — add it to the category rules before processing.");
    }
}
```

`DomainException` es la clase base del proyecto (extiende
`RuntimeException`). El controller externo (fuera del dominio) la
traduce a RFC 7807 `ProblemDetail`. En el dominio no se decide el código
HTTP.

## Dinero

Reglas explícitas — **reglas duras del proyecto** (docs/base-standards.md §4):

- Representación: `BigDecimal` con escala 2 y `RoundingMode.HALF_EVEN`.
  `double` o `float` en el dominio **es un bug**.
- Redondeo solo en los puntos definidos en la spec de la feature.
  Nada de `setScale` ad-hoc disperso.
- Comparaciones de monto: `compareTo`, **nunca** `equals` (dos
  `BigDecimal` iguales en valor pero distinta escala no son `equals`).
- En tests: `assertThat(result).isEqualByComparingTo(new BigDecimal("123.45"))`.
  Verifica también la escala cuando la spec la fija:
  ```java
  assertThat(result.scale()).isEqualTo(2);
  ```

## Tiempo

- `OffsetDateTime` en UTC para todo lo que entra o sale del dominio.
- Conversión a `America/Bogota` solo en presentación (fuera del dominio).
- "Ahora" entra siempre como parámetro (`OffsetDateTime now` o
  `Clock clock`); nunca se llama al reloj del sistema dentro del dominio.

## Determinismo

Toda función pura del dominio debe ser idempotente sobre el mismo input.
Los tests pueden incluir un caso explícito:

```java
@Test
@DisplayName("@s4 — shouldBeDeterministicOnSameInput")
void shouldBeDeterministicOnSameInput() {
    var input = buildInput();
    var a = resolver.resolve(input);
    var b = resolver.resolve(input);
    assertThat(a).isEqualTo(b);
}
```

## Logs

En el dominio puro no hay logs. Si algo merece registrarse, devuélvelo
en la salida (p. ej. una lista de `ValidationWarning[]`). Los logs
pertenecen a la capa de servicio (fuera del scope de este harness).

## Comentarios

Default: sin comentarios. Solo cuando el **por qué** es no obvio: una
restricción de la spec, una decisión histórica, un workaround conocido.
Los nombres revelan el "qué"; los comentarios explican el "por qué no obvio".
