package com.myfinanceview.domain.merchant;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Frozen merchant normalization heuristic — see design.md D12 + tasks.md §5.9-5.10.
 *
 * <p>Produces the {@code raw_pattern} value used by {@code myfinance.merchants.UNIQUE (user_id,
 * raw_pattern)}; any change to this function post-merge requires a dedicated change with a
 * re-mapping plan (existing merchants risk colliding or splitting).
 *
 * <p>Reglas aplicadas en orden:
 * <ol>
 *   <li>{@code null} → {@code ""}.</li>
 *   <li>{@code trim()} + {@code toLowerCase(Locale.ROOT)}.</li>
 *   <li>Strip de trailing-digits típicos de POS: {@code \s*\*?\s*\d{2,}\s*$} (umbral 2+ dígitos
 *       para no comer {@code "DIDI 7"} → {@code "didi 7"}).</li>
 *   <li>Collapse de runs de whitespace a un único space.</li>
 *   <li>{@code trim()} final.</li>
 * </ol>
 *
 * <p>NOT stripped: accents, emojis, special chars. The merchant management UI (future) handles
 * cleanup if duplicates appear.
 */
public final class MerchantNormalizer {

    // FROZEN: see design.md D12 + tasks.md §5.9-5.10.
    // Trailing 2+ digit run, optionally preceded by '*', optionally surrounded by whitespace.
    // Note the inner \s* between '*' and digits: matches "JUAN VALDEZ *9876" AND "RAPPI 42".
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\s*\\*?\\s*\\d{2,}\\s*$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private MerchantNormalizer() {
        throw new AssertionError("static utility — do not instantiate");
    }

    /**
     * Normalize a bank-provided description into a stable merchant pattern.
     *
     * @param raw the raw description (may be {@code null}).
     * @return the normalized pattern, never {@code null}; empty string for {@code null}/blank input.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = TRAILING_DIGITS.matcher(s).replaceAll("");
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ");
        return s.trim();
    }
}
