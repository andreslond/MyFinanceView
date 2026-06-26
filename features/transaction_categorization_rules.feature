Feature: Categorización determinista por reglas (dominio category)
  Dado un movimiento normalizado (descriptor, monto, tipo) y una lista de
  reglas de dominio, la función pura
  TransactionCategorizer.categorize(transaction, rules) devuelve un
  CategoryMatch determinista e idempotente:
    - Matched(CategoryRef) si una o más reglas coinciden con la misma categoría.
    - NoMatch si ninguna regla coincide, la lista está vacía, o hay ambigüedad
      entre categorías distintas (anti-adivinanza: nunca se inventa un fallback).
  Una regla coincide si y solo si todos sus predicados no-nulos/no-vacíos son
  verdaderos simultáneamente (semántica AND). Un predicado ausente no filtra.
  Sin IO, sin reloj, sin Spring, sin jOOQ.

  # ── Match por patrón de comercio ───────────────────────────────────────────

  @s1
  Scenario: una regla coincide por patrón de comercio — Matched con la categoría
    Given una transacción con descriptor "RAPPI COLOMBIA S.A.S", monto 25000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "RAPPI" y categoría (id "a1b2c3d4-0001-0001-0001-000000000001", name "Dining Out")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0001-0001-0001-000000000001", name "Dining Out")

  @s2
  Scenario: match case-insensitive — patrón en minúscula, descriptor en mayúscula
    Given una transacción con descriptor "RAPPI COLOMBIA S.A.S", monto 25000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "rappi" y categoría (id "a1b2c3d4-0001-0001-0001-000000000001", name "Dining Out")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0001-0001-0001-000000000001", name "Dining Out")

  # ── NoMatch por ausencia de regla que coincida ──────────────────────────────

  @s3
  Scenario: ninguna regla coincide con el descriptor — NoMatch
    Given una transacción con descriptor "TRANSACCION DESCONOCIDA", monto 10000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con merchantPattern "RAPPI" y categoría (id "a1b2c3d4-0001-0001-0001-000000000001", name "Dining Out")
    When  se categoriza la transacción con esa regla
    Then  el resultado es NoMatch

  @s4
  Scenario: lista de reglas vacía — NoMatch
    Given una transacción con descriptor "BOLD*RESTAURANTE LA PLAZA", monto 50000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una lista de reglas vacía
    When  se categoriza la transacción con esa lista
    Then  el resultado es NoMatch

  # ── Varias reglas coinciden con la MISMA categoría ──────────────────────────

  @s5
  Scenario: varias reglas coinciden y apuntan a la misma categoría — Matched
    Given una transacción con descriptor "DIDI FOOD BOGOTA", monto 30000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "DIDI" y categoría (id "a1b2c3d4-0002-0002-0002-000000000002", name "Dining Out")
    And   una segunda regla con merchantPattern "FOOD" y categoría (id "a1b2c3d4-0002-0002-0002-000000000002", name "Dining Out")
    When  se categoriza la transacción con ambas reglas
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0002-0002-0002-000000000002", name "Dining Out")

  # ── Varias reglas coinciden con categorías DISTINTAS (conflicto) ─────────────

  @s6
  Scenario: varias reglas coinciden con categorías distintas — NoMatch por ambigüedad
    Given una transacción con descriptor "UBER EATS COLOMBIA", monto 45000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "UBER" y categoría (id "a1b2c3d4-0003-0003-0003-000000000003", name "Transport")
    And   una segunda regla con merchantPattern "EATS" y categoría (id "a1b2c3d4-0004-0004-0004-000000000004", name "Dining Out")
    When  se categoriza la transacción con ambas reglas
    Then  el resultado es NoMatch

  # ── Predicado de rango de monto ─────────────────────────────────────────────

  @s7
  Scenario: regla con predicado minAmount y maxAmount — monto dentro del rango coincide
    Given una transacción con descriptor "SUPERMERCADO EXITO", monto 80000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con minAmount 50000.00, maxAmount 100000.00 y categoría (id "a1b2c3d4-0005-0005-0005-000000000005", name "Groceries")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0005-0005-0005-000000000005", name "Groceries")

  @s8
  Scenario: regla con predicado maxAmount — monto fuera del rango no coincide
    Given una transacción con descriptor "SUPERMERCADO EXITO", monto 150000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con minAmount 50000.00, maxAmount 100000.00 y categoría (id "a1b2c3d4-0005-0005-0005-000000000005", name "Groceries")
    When  se categoriza la transacción con esa regla
    Then  el resultado es NoMatch

  @s9
  Scenario: regla con solo minAmount sin maxAmount — monto exactamente en el límite inferior coincide
    Given una transacción con descriptor "FARMACIA DROGUERIA", monto 20000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con minAmount 20000.00 (sin maxAmount) y categoría (id "a1b2c3d4-0006-0006-0006-000000000006", name "Health")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0006-0006-0006-000000000006", name "Health")

  @s10
  Scenario: monto exactamente igual a minAmount == maxAmount — restricción puntual coincide
    Given una transacción con descriptor "PEAJE NORTE", monto 5800.00 COP y tipo DEBIT_PURCHASE
    And   una regla con minAmount 5800.00, maxAmount 5800.00 y categoría (id "a1b2c3d4-0007-0007-0007-000000000007", name "Transport")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0007-0007-0007-000000000007", name "Transport")

  # ── Predicado de TransactionKind ────────────────────────────────────────────

  @s11
  Scenario: regla con predicado de tipo — coincide solo con el tipo correcto (CREDIT_CARD_PURCHASE)
    Given una transacción con descriptor "NETFLIX", monto 17900.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "NETFLIX", transactionKinds [CREDIT_CARD_PURCHASE] y categoría (id "a1b2c3d4-0008-0008-0008-000000000008", name "Entertainment")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0008-0008-0008-000000000008", name "Entertainment")

  @s12
  Scenario: regla con predicado de tipo — no coincide cuando el tipo es distinto (INCOMING_TRANSFER)
    Given una transacción con descriptor "NETFLIX", monto 17900.00 COP y tipo INCOMING_TRANSFER
    And   una regla con merchantPattern "NETFLIX", transactionKinds [CREDIT_CARD_PURCHASE] y categoría (id "a1b2c3d4-0008-0008-0008-000000000008", name "Entertainment")
    When  se categoriza la transacción con esa regla
    Then  el resultado es NoMatch

  # ── Predicados combinados (AND semántica) ───────────────────────────────────

  @s13
  Scenario: regla con merchantPattern y minAmount combinados — coincide solo si AMBOS predicados se cumplen
    Given una transacción con descriptor "RAPPI MARKET", monto 75000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con merchantPattern "RAPPI", minAmount 50000.00 y categoría (id "a1b2c3d4-0009-0009-0009-000000000009", name "Groceries")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0009-0009-0009-000000000009", name "Groceries")

  @s14
  Scenario: regla con merchantPattern y minAmount combinados — no coincide si el monto está por debajo del umbral
    Given una transacción con descriptor "RAPPI MARKET", monto 20000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con merchantPattern "RAPPI", minAmount 50000.00 y categoría (id "a1b2c3d4-0009-0009-0009-000000000009", name "Groceries")
    When  se categoriza la transacción con esa regla
    Then  el resultado es NoMatch

  # ── Anti-adivinanza: reglas inválidas lanzan InvalidRuleException ───────────

  @s15
  Scenario: regla sin ningún predicado (catch-all total) — lanza InvalidRuleException
    Given una transacción con descriptor "CUALQUIER COMERCIO", monto 10000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla sin merchantPattern, sin minAmount, sin maxAmount y con transactionKinds vacío
    And   esa regla tiene categoría (id "a1b2c3d4-0010-0010-0010-000000000010", name "Miscellaneous")
    When  se intenta categorizar la transacción con esa regla
    Then  se lanza InvalidRuleException con mensaje que contiene "has no predicates"

  @s16
  Scenario: merchantPattern en blanco (solo espacios) — lanza InvalidRuleException
    Given una transacción con descriptor "TIENDA ONLINE", monto 30000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "   " (solo espacios en blanco) y categoría (id "a1b2c3d4-0011-0011-0011-000000000011", name "Shopping")
    When  se intenta categorizar la transacción con esa regla
    Then  se lanza InvalidRuleException con mensaje que contiene "merchantPattern must not be blank"

  @s17
  Scenario: merchantPattern vacío ("") — lanza InvalidRuleException
    Given una transacción con descriptor "TIENDA ONLINE", monto 30000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "" (cadena vacía) y categoría (id "a1b2c3d4-0011-0011-0011-000000000011", name "Shopping")
    When  se intenta categorizar la transacción con esa regla
    Then  se lanza InvalidRuleException con mensaje que contiene "merchantPattern must not be blank"

  @s18
  Scenario: regla con minAmount mayor que maxAmount — lanza InvalidRuleException
    Given una transacción con descriptor "COMPRA VARIA", monto 50000.00 COP y tipo DEBIT_PURCHASE
    And   una regla con minAmount 100000.00, maxAmount 50000.00 y categoría (id "a1b2c3d4-0012-0012-0012-000000000012", name "Shopping")
    When  se intenta categorizar la transacción con esa regla
    Then  se lanza InvalidRuleException con mensaje que contiene "minAmount must be <= maxAmount"

  # ── Determinismo / idempotencia ─────────────────────────────────────────────

  @s19
  Scenario: mismo input con las mismas reglas produce siempre el mismo resultado — idempotencia
    Given una transacción con descriptor "BOLD*CAFE EL RINCON", monto 12000.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "BOLD" y categoría (id "a1b2c3d4-0013-0013-0013-000000000013", name "Dining Out")
    When  se categoriza la transacción dos veces con la misma regla
    Then  ambos resultados son Matched con CategoryRef (id "a1b2c3d4-0013-0013-0013-000000000013", name "Dining Out") y son idénticos

  # ── Casos límite de monto ───────────────────────────────────────────────────

  @s20
  Scenario: monto cero es válido — no lanza excepción y puede coincidir con una regla
    Given una transacción con descriptor "DEVOLUCION RAPPI", monto 0.00 COP y tipo CREDIT_CARD_PURCHASE
    And   una regla con merchantPattern "DEVOLUCION" y categoría (id "a1b2c3d4-0014-0014-0014-000000000014", name "Refunds")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0014-0014-0014-000000000014", name "Refunds")

  # ── transactionKinds vacío en regla: sin restricción de tipo ────────────────

  @s21
  Scenario: transactionKinds vacío en la regla — coincide con cualquier tipo de transacción
    Given una transacción con descriptor "UBER PASS", monto 9900.00 COP y tipo INCOMING_PAYMENT
    And   una regla con merchantPattern "UBER PASS", transactionKinds vacío y categoría (id "a1b2c3d4-0015-0015-0015-000000000015", name "Transport")
    When  se categoriza la transacción con esa regla
    Then  el resultado es Matched con CategoryRef (id "a1b2c3d4-0015-0015-0015-000000000015", name "Transport")
