# Gherkin — el contrato ejecutable

> "Create a set of .feature files from the project-spec.md." Cada
> comportamiento se vuelve un `Scenario` verificable. La ambigüedad se
> resuelve antes de escribir código, en la puerta humana.

## Variante light (sin cucumber-jvm)

No instalamos el runner de Cucumber. El `.feature` sirve como **contrato
humano** y el mapeo a tests es **mecánico, por convención de nombres**:

- Cada `Scenario:` se tagea con `@s1`, `@s2`, … (en la línea anterior).
- En JUnit 5, el `@DisplayName` del test correspondiente **empieza con el
  tag** y sigue la convención `should{Result}When{Condition}`.

Ejemplo:

```gherkin
@s1
Scenario: fecha posterior al día de corte cae en el período siguiente
  Given un día de corte 15 y un movimiento con fecha "2026-03-20"
  When  se resuelve el período de facturación
  Then  el período va del "2026-03-16" al "2026-04-15"
```

```java
// backend/src/test/java/com/myfinanceview/domain/billing/BillingPeriodResolverTest.java
@Test
@DisplayName("@s1 — shouldResolveNextPeriodWhenDateAfterCutDay")
void shouldResolveNextPeriodWhenDateAfterCutDay() {
    var result = BillingPeriodResolver.resolve(cutDay(15), date("2026-03-20"));
    assertThat(result.start()).isEqualTo(date("2026-03-16"));
    assertThat(result.end()).isEqualTo(date("2026-04-15"));
}
```

El `judge` busca el tag textualmente con:
```
grep -REn '@s<N>' backend/src/test/java/com/myfinanceview/domain
```
El `tdd_craftsman` lo registra en `progress/tdd_<name>.md`. No hace falta más.

## Cómo escribir un buen `.feature`

- **Una `Feature:` por archivo**, con el propósito en una línea.
- **Un `Scenario:` por comportamiento observable**, incluyendo casos
  límite y errores.
- **Pasos concretos.** Nada de "el sistema funciona". Cada `Then` afirma
  algo medible: un valor exacto (BigDecimal con escala), un campo del
  resultado, una excepción concreta con mensaje, un estado en string.
- **Determinismo.** Si el escenario depende de "ahora", `Given` lo fija:
  `Given que el reloj marca "2026-05-12T00:00:00Z"`.
- **Regla anti-adivinanza.** Cualquier feature con dinero incluye un
  escenario con una entrada que el sistema debería rechazar: categoría
  desconocida, tarifa no encontrada, regla no clasificada. La regla es
  **lanzar**, no fallback silencioso.

## Lo que NO va en un `.feature`

- Detalles de implementación (qué clase, qué firma exacta, qué
  estructura interna). Eso vive en `project-spec.md`.
- Estructuras de tablas Postgres. El dominio puro no las conoce.
- IO, jOOQ ni Spring. El dominio es puro.

## Cuándo volver al `spec_partner`

Si al destilar un escenario el `gherkin_author` no encuentra cómo
expresarlo en Given/When/Then medible, **no inventa**: devuelve la
pregunta al `spec_partner`. La spec está incompleta o el contrato es
ambiguo.
