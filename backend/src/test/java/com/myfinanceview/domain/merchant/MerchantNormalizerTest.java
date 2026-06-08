package com.myfinanceview.domain.merchant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frozen-behaviour test for {@link MerchantNormalizer#normalize(String)} — design.md D12.
 *
 * <p>If any of these assertions break, the contract has changed; merging that change requires a
 * re-mapping plan for existing {@code myfinance.merchants.raw_pattern} rows. See tasks.md §5.10.
 */
class MerchantNormalizerTest {

    @Test
    void shouldReturnEmptyStringWhenInputIsNull() {
        assertThat(MerchantNormalizer.normalize(null)).isEqualTo("");
    }

    @Test
    void shouldReturnEmptyStringWhenInputIsEmpty() {
        assertThat(MerchantNormalizer.normalize("")).isEqualTo("");
    }

    @Test
    void shouldReturnEmptyStringWhenInputIsOnlyWhitespace() {
        assertThat(MerchantNormalizer.normalize("   ")).isEqualTo("");
    }

    @Test
    void shouldStripStarPrefixedTrailingDigitsWhenPosSuffixPresent() {
        assertThat(MerchantNormalizer.normalize("NETFLIX.COM *1234")).isEqualTo("netflix.com");
    }

    @Test
    void shouldStripBareTrailingDigitsWhenTwoOrMore() {
        assertThat(MerchantNormalizer.normalize("RAPPI 42")).isEqualTo("rappi");
    }

    @Test
    void shouldPreserveSingleTrailingDigitWhenLessThanTwo() {
        // 1 digit MUST NOT be stripped — the regex threshold is 2+
        assertThat(MerchantNormalizer.normalize("DIDI 7")).isEqualTo("didi 7");
    }

    @Test
    void shouldCollapseInnerWhitespaceAndStripStarSuffixWhenMessyInput() {
        assertThat(MerchantNormalizer.normalize("  JUAN  VALDEZ  *9876 ")).isEqualTo("juan valdez");
    }

    @Test
    void shouldLowercaseAndPreserveDotsWhenInputIsSimpleDomain() {
        assertThat(MerchantNormalizer.normalize("APPLE.COM")).isEqualTo("apple.com");
    }

    @Test
    void shouldNotStripMidStringDigitsWhenTrailingTokenIsLetters() {
        // Digits NOT at the end remain in place — the strip pattern is anchored at $
        assertThat(MerchantNormalizer.normalize("FOO 123 BAR")).isEqualTo("foo 123 bar");
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
        'AMAZON.COM*4ABCD9'                  , amazon.com*4abcd9
        'PAYU * COL  *55'                    , payu * col
        'STARBUCKS  9'                       , starbucks 9
        """)
    void shouldHandleEdgeCasesPerFrozenRule(String input, String expected) {
        // Sanity additional edges:
        //  - non-trailing digits stay (amazon row has trailing letter so NOT stripped)
        //  - "STARBUCKS  9" has 1 trailing digit so NOT stripped, but inner whitespace
        //    collapses to a single space.
        assertThat(MerchantNormalizer.normalize(input)).isEqualTo(expected);
    }
}
