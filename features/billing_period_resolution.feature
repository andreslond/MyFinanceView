Feature: Resolución del período de facturación por día de corte
  Dado un día de corte (cutDay ∈ [1..31]) y la fecha de un movimiento
  (transactionDate: LocalDate), la función pura
  BillingPeriodResolver.resolve(cutDay, transactionDate) devuelve el
  período de facturación BillingPeriod(start, end) — intervalo cerrado,
  ambos días inclusivos — al que pertenece el movimiento.
  La función no conoce la tarjeta ni la DB; no llama al reloj del sistema.
  Cuando cutDay supera los días del mes, aplica clamping determinista:
  effectiveCutDay = min(cutDay, lastDayOfMonth). Fuera de [1..31] lanza
  InvalidCutDayException.

  # ── Regla de bordes ────────────────────────────────────────────────────────

  @s1
  Scenario: fecha igual al día de corte pertenece al período actual — borde inclusivo
    Given un día de corte 15 y un movimiento con fecha "2026-05-15"
    When  se resuelve el período de facturación
    Then  el período va del "2026-04-16" al "2026-05-15"

  @s2
  Scenario: fecha igual al día de corte más uno pasa al período siguiente
    Given un día de corte 15 y un movimiento con fecha "2026-05-16"
    When  se resuelve el período de facturación
    Then  el período va del "2026-05-16" al "2026-06-15"

  @s3
  Scenario: ejemplo canónico — fecha posterior al corte en el mismo mes
    Given un día de corte 15 y un movimiento con fecha "2026-05-20"
    When  se resuelve el período de facturación
    Then  el período va del "2026-05-16" al "2026-06-15"

  # ── Clamping en meses cortos ────────────────────────────────────────────────

  @s4
  Scenario: cutDay 31 en febrero — fecha antes del corte efectivo del mes
    Given un día de corte 31 y un movimiento con fecha "2026-02-15"
    When  se resuelve el período de facturación
    Then  el período va del "2026-02-01" al "2026-02-28"

  @s5
  Scenario: cutDay 31 en febrero — fecha igual al último día del mes corto
    Given un día de corte 31 y un movimiento con fecha "2026-02-28"
    When  se resuelve el período de facturación
    Then  el período va del "2026-02-01" al "2026-02-28"

  @s6
  Scenario: cutDay 31 en abril — mes siguiente también tiene clamp (30 días)
    Given un día de corte 31 y un movimiento con fecha "2026-04-05"
    When  se resuelve el período de facturación
    Then  el período va del "2026-04-01" al "2026-04-30"

  # ── Corte 29 y febrero ──────────────────────────────────────────────────────

  @s7
  Scenario: corte 29 en febrero de año no bisiesto — clamp en start y end
    Given un día de corte 29 y un movimiento con fecha "2025-02-15"
    When  se resuelve el período de facturación
    Then  el período va del "2025-01-30" al "2025-02-28"

  @s8
  Scenario: corte 29 en febrero de año bisiesto — el día 29 es efectivo
    Given un día de corte 29 y un movimiento con fecha "2024-02-29"
    When  se resuelve el período de facturación
    Then  el período va del "2024-01-30" al "2024-02-29"

  # ── Cruce de año ────────────────────────────────────────────────────────────

  @s9
  Scenario: cruce de año — fecha en enero pertenece a período que cierra en enero
    Given un día de corte 15 y un movimiento con fecha "2026-01-05"
    When  se resuelve el período de facturación
    Then  el período va del "2025-12-16" al "2026-01-15"

  @s10
  Scenario: transición de año en inicio del período — cutDay 31 en enero
    Given un día de corte 31 y un movimiento con fecha "2026-01-05"
    When  se resuelve el período de facturación
    Then  el período va del "2026-01-01" al "2026-01-31"

  # ── Borde inferior válido ───────────────────────────────────────────────────

  @s11
  Scenario: cutDay 1 es el borde inferior válido y produce un período correcto
    Given un día de corte 1 y un movimiento con fecha "2026-05-01"
    When  se resuelve el período de facturación
    Then  el período va del "2026-04-02" al "2026-05-01"

  # ── Anti-adivinanza: cutDay fuera de rango [1..31] lanza excepción ──────────

  @s12
  Scenario: cutDay 0 lanza InvalidCutDayException — límite inferior fuera de rango
    Given un día de corte 0 y un movimiento con fecha "2026-05-15"
    When  se intenta resolver el período de facturación
    Then  se lanza InvalidCutDayException con mensaje que contiene "cutDay 0 is out of valid range [1..31]"

  @s13
  Scenario: cutDay 32 lanza InvalidCutDayException — límite superior fuera de rango
    Given un día de corte 32 y un movimiento con fecha "2026-05-15"
    When  se intenta resolver el período de facturación
    Then  se lanza InvalidCutDayException con mensaje que contiene "cutDay 32 is out of valid range [1..31]"
