package com.myfinanceview.domain.merchant;

import com.myfinanceview.jooq.generated.tables.records.MerchantsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.MERCHANTS;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.least;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.SQLDataType.NUMERIC;

/**
 * Access to {@code myfinance.merchants}, scoped to the authenticated user. Implements the
 * primitive operations needed by {@code MerchantUpserter} (design.md D5).
 *
 * <p><b>Isolation invariant</b>: every method takes {@code userId} and filters by it. Even the
 * UPSERT in {@link #upsertByRawPattern} writes {@code user_id} explicitly so the UNIQUE
 * {@code (user_id, raw_pattern)} keeps user-A and user-B patterns separate.
 */
@Repository
public class MerchantRepository {

    private static final BigDecimal CONFIDENCE_INCREMENT = new BigDecimal("0.10");
    private static final BigDecimal CONFIDENCE_CAP       = new BigDecimal("1.00");
    private static final BigDecimal CONFIDENCE_RESET     = new BigDecimal("0.50");

    private final DSLContext dsl;

    public MerchantRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<MerchantsRecord> findById(UUID id, UUID userId) {
        return Optional.ofNullable(
            dsl.selectFrom(MERCHANTS)
                .where(MERCHANTS.ID.eq(id))
                .and(MERCHANTS.USER_ID.eq(userId))
                .fetchOne());
    }

    /**
     * Idempotent UPSERT keyed by {@code (user_id, raw_pattern)}. Protects against the race where
     * the user PATCHes a transaction while n8n ingestion writes another transaction with the same
     * normalized merchant pattern (D5).
     *
     * <p>"First learning" semantics: {@code confidence = 0.50}, {@code match_count = 1},
     * {@code last_confirmed_at = NOW()}. On conflict the same values overwrite — the call site is
     * either the very first PATCH for this pattern (insert wins) or a re-issue of the same first
     * PATCH (update path, but ends in the same state).
     *
     * @return the merchant id (existing or newly inserted).
     */
    public UUID upsertByRawPattern(UUID userId, String rawPattern, String displayName, UUID categoryId) {
        return dsl.insertInto(MERCHANTS)
            .set(MERCHANTS.USER_ID,           userId)
            .set(MERCHANTS.RAW_PATTERN,       rawPattern)
            .set(MERCHANTS.DISPLAY_NAME,      displayName)
            .set(MERCHANTS.CATEGORY_ID,       categoryId)
            .set(MERCHANTS.CONFIDENCE,        CONFIDENCE_RESET)
            .set(MERCHANTS.MATCH_COUNT,       1)
            .set(MERCHANTS.LAST_CONFIRMED_AT, currentOffsetDateTime())
            .onConflict(MERCHANTS.USER_ID, MERCHANTS.RAW_PATTERN)
            .doUpdate()
            .set(MERCHANTS.CATEGORY_ID,       categoryId)
            .set(MERCHANTS.CONFIDENCE,        CONFIDENCE_RESET)
            .set(MERCHANTS.MATCH_COUNT,       1)
            .set(MERCHANTS.LAST_CONFIRMED_AT, currentOffsetDateTime())
            .set(MERCHANTS.UPDATED_AT,        currentOffsetDateTime())
            .returningResult(MERCHANTS.ID)
            .fetchOne()
            .value1();
    }

    /**
     * Re-confirm the merchant's current category (D5 Branch A, "same as before"). Increments
     * {@code confidence} by {@code +0.10}, capped at {@code 1.00}; bumps {@code match_count} and
     * {@code last_confirmed_at}. Returns rows affected — 0 means the merchant did not belong to
     * this user (isolation hit).
     */
    public int confirmCategory(UUID merchantId, UUID userId) {
        // least(...) widens to Field<Serializable> without an explicit type hint; cast via val(...)
        // with NUMERIC datatype so the .set(...) overload resolves to set(Field<BigDecimal>, Field<BigDecimal>).
        var incremented = MERCHANTS.CONFIDENCE.add(val(CONFIDENCE_INCREMENT, NUMERIC));
        var capped = least(val(CONFIDENCE_CAP, NUMERIC), incremented).cast(BigDecimal.class);
        return dsl.update(MERCHANTS)
            .set(MERCHANTS.CONFIDENCE,        capped)
            .set(MERCHANTS.MATCH_COUNT,       MERCHANTS.MATCH_COUNT.add(1))
            .set(MERCHANTS.LAST_CONFIRMED_AT, currentOffsetDateTime())
            .set(MERCHANTS.UPDATED_AT,        currentOffsetDateTime())
            .where(MERCHANTS.ID.eq(merchantId))
            .and(MERCHANTS.USER_ID.eq(userId))
            .execute();
    }

    /**
     * Reset the merchant on category drift (D5 Branch A, "user disagrees"): swap to the new
     * category, set {@code confidence = 0.50}, {@code match_count = 1}, {@code last_confirmed_at
     * = NOW()}. {@code display_name} is intentionally NOT touched (D5 paragraph: identity stays,
     * classification resets).
     */
    public int resetForDrift(UUID merchantId, UUID userId, UUID newCategoryId) {
        return dsl.update(MERCHANTS)
            .set(MERCHANTS.CATEGORY_ID,       newCategoryId)
            .set(MERCHANTS.CONFIDENCE,        CONFIDENCE_RESET)
            .set(MERCHANTS.MATCH_COUNT,       1)
            .set(MERCHANTS.LAST_CONFIRMED_AT, currentOffsetDateTime())
            .set(MERCHANTS.UPDATED_AT,        currentOffsetDateTime())
            .where(MERCHANTS.ID.eq(merchantId))
            .and(MERCHANTS.USER_ID.eq(userId))
            .execute();
    }
}
