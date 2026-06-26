package com.myfinanceview.domain.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD suite for {@link BillingPeriodResolver#resolve(int, LocalDate)}.
 * Each test maps 1-to-1 to a Gherkin scenario in
 * features/billing_period_resolution.feature.
 */
class BillingPeriodResolverTest {

    // ── @s1 — Regla de bordes: fecha igual al día de corte ─────────────────

    @Test
    @DisplayName("@s1 — shouldReturnCurrentPeriodWhenDateEqualsCutDay")
    void shouldReturnCurrentPeriodWhenDateEqualsCutDay() {
        BillingPeriod period = BillingPeriodResolver.resolve(15, LocalDate.of(2026, 5, 15));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 4, 16));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 5, 15));
    }

    // ── @s2 — Fecha igual al corte + 1 → período siguiente ─────────────────

    @Test
    @DisplayName("@s2 — shouldReturnNextPeriodWhenDateIsOneDayAfterCutDay")
    void shouldReturnNextPeriodWhenDateIsOneDayAfterCutDay() {
        BillingPeriod period = BillingPeriodResolver.resolve(15, LocalDate.of(2026, 5, 16));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 5, 16));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    // ── @s3 — Ejemplo canónico: fecha posterior al corte ───────────────────

    @Test
    @DisplayName("@s3 — shouldReturnNextPeriodWhenDateIsAfterCutDayInSameMonth")
    void shouldReturnNextPeriodWhenDateIsAfterCutDayInSameMonth() {
        BillingPeriod period = BillingPeriodResolver.resolve(15, LocalDate.of(2026, 5, 20));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 5, 16));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 6, 15));
    }

    // ── @s4 — Clamping: cutDay 31 en febrero, fecha antes del corte efectivo

    @Test
    @DisplayName("@s4 — shouldClampEndToFeb28WhenCutDay31AndFebruaryShort")
    void shouldClampEndToFeb28WhenCutDay31AndFebruaryShort() {
        BillingPeriod period = BillingPeriodResolver.resolve(31, LocalDate.of(2026, 2, 15));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    // ── @s5 — Clamping: cutDay 31 en febrero, fecha = último día del mes ───

    @Test
    @DisplayName("@s5 — shouldClampEndToFeb28WhenCutDay31AndDateIsLastDayOfFeb")
    void shouldClampEndToFeb28WhenCutDay31AndDateIsLastDayOfFeb() {
        BillingPeriod period = BillingPeriodResolver.resolve(31, LocalDate.of(2026, 2, 28));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    // ── @s6 — Clamping: cutDay 31 en abril (30 días) ───────────────────────

    @Test
    @DisplayName("@s6 — shouldClampEndToApr30WhenCutDay31AndAprilIsShort")
    void shouldClampEndToApr30WhenCutDay31AndAprilIsShort() {
        BillingPeriod period = BillingPeriodResolver.resolve(31, LocalDate.of(2026, 4, 5));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    // ── @s7 — Corte 29 en febrero de año no bisiesto ───────────────────────

    @Test
    @DisplayName("@s7 — shouldClampBothBoundsWhenCutDay29AndNonLeapFebruary")
    void shouldClampBothBoundsWhenCutDay29AndNonLeapFebruary() {
        BillingPeriod period = BillingPeriodResolver.resolve(29, LocalDate.of(2025, 2, 15));

        assertThat(period.start()).isEqualTo(LocalDate.of(2025, 1, 30));
        assertThat(period.end()).isEqualTo(LocalDate.of(2025, 2, 28));
    }

    // ── @s8 — Corte 29 en febrero de año bisiesto ──────────────────────────

    @Test
    @DisplayName("@s8 — shouldUseDay29WhenCutDay29AndLeapYearFebruary")
    void shouldUseDay29WhenCutDay29AndLeapYearFebruary() {
        BillingPeriod period = BillingPeriodResolver.resolve(29, LocalDate.of(2024, 2, 29));

        assertThat(period.start()).isEqualTo(LocalDate.of(2024, 1, 30));
        assertThat(period.end()).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    // ── @s9 — Cruce de año: fecha en enero pertenece al período previo ─────

    @Test
    @DisplayName("@s9 — shouldCrossYearBoundaryWhenJanuaryDateBeforeCutDay")
    void shouldCrossYearBoundaryWhenJanuaryDateBeforeCutDay() {
        BillingPeriod period = BillingPeriodResolver.resolve(15, LocalDate.of(2026, 1, 5));

        assertThat(period.start()).isEqualTo(LocalDate.of(2025, 12, 16));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    // ── @s10 — Cruce de año en inicio del período: cutDay 31 en enero ──────

    @Test
    @DisplayName("@s10 — shouldStartOnJan1WhenPrevMonthIsDecAnd31ClampsToDec31ThenPlusOne")
    void shouldStartOnJan1WhenPrevMonthIsDecAnd31ClampsToDec31ThenPlusOne() {
        BillingPeriod period = BillingPeriodResolver.resolve(31, LocalDate.of(2026, 1, 5));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    // ── @s11 — Borde inferior válido: cutDay 1 ─────────────────────────────

    @Test
    @DisplayName("@s11 — shouldReturnCorrectPeriodWhenCutDay1IsLowerBound")
    void shouldReturnCorrectPeriodWhenCutDay1IsLowerBound() {
        BillingPeriod period = BillingPeriodResolver.resolve(1, LocalDate.of(2026, 5, 1));

        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    // ── @s12 — cutDay 0 lanza InvalidCutDayException ───────────────────────

    @Test
    @DisplayName("@s12 — shouldThrowInvalidCutDayExceptionWhenCutDayIsZero")
    void shouldThrowInvalidCutDayExceptionWhenCutDayIsZero() {
        assertThatThrownBy(() -> BillingPeriodResolver.resolve(0, LocalDate.of(2026, 5, 15)))
                .isInstanceOf(InvalidCutDayException.class)
                .hasMessageContaining("cutDay 0 is out of valid range [1..31]");
    }

    // ── @s13 — cutDay 32 lanza InvalidCutDayException ──────────────────────

    @Test
    @DisplayName("@s13 — shouldThrowInvalidCutDayExceptionWhenCutDayIs32")
    void shouldThrowInvalidCutDayExceptionWhenCutDayIs32() {
        assertThatThrownBy(() -> BillingPeriodResolver.resolve(32, LocalDate.of(2026, 5, 15)))
                .isInstanceOf(InvalidCutDayException.class)
                .hasMessageContaining("cutDay 32 is out of valid range [1..31]");
    }

    // ── Idempotencia/Determinismo ───────────────────────────────────────────

    @Test
    @DisplayName("shouldReturnSamePeriodWhenCalledTwiceWithSameInput")
    void shouldReturnSamePeriodWhenCalledTwiceWithSameInput() {
        BillingPeriod first  = BillingPeriodResolver.resolve(15, LocalDate.of(2026, 5, 20));
        BillingPeriod second = BillingPeriodResolver.resolve(15, LocalDate.of(2026, 5, 20));

        assertThat(first).isEqualTo(second);
    }
}
