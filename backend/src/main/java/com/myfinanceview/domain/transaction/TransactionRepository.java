package com.myfinanceview.domain.transaction;

import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;
import static org.jooq.impl.DSL.noCondition;

/**
 * Access to {@code myfinance.transactions}, scoped to the authenticated user.
 *
 * <p><b>Isolation invariant</b> (design.md D2): every method takes {@code userId} and includes
 * {@code WHERE user_id = ?}. {@link #updateCategory} and {@link #assignMerchantId} return the
 * number of rows affected so the caller can detect a cross-user attempt (0 affected) without
 * needing to differentiate "not found" vs "owned by another user" — both collapse to 404.
 */
@Repository
public class TransactionRepository {

    private final DSLContext dsl;

    public TransactionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Paginated listing for {@code GET /api/v1/transactions}. Returns up to {@code pageSize + 1}
     * rows so the service layer can compute {@code hasMore} by checking whether the count exceeds
     * {@code pageSize} (D4).
     *
     * <p>Ordering: {@code occurred_at DESC, id DESC} — the second criterion stabilises pages when
     * many transactions share an {@code occurred_at} timestamp (typical with n8n batch ingestion).
     *
     * @param userId       authenticated user.
     * @param accountId    optional account filter; empty Optional means "no filter".
     * @param categoryIds  optional category filter; empty list means "no filter".
     * @param page         1-based page index.
     * @param pageSize     desired page size; this method fetches {@code pageSize + 1}.
     */
    public List<TransactionsRecord> findPage(
        UUID userId,
        Optional<UUID> accountId,
        List<UUID> categoryIds,
        int page,
        int pageSize
    ) {
        Condition where = TRANSACTIONS.USER_ID.eq(userId);
        if (accountId.isPresent()) {
            where = where.and(TRANSACTIONS.ACCOUNT_ID.eq(accountId.get()));
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            where = where.and(TRANSACTIONS.CATEGORY_ID.in(categoryIds));
        } else {
            where = where.and(noCondition());
        }

        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, pageSize);
        int offset = (safePage - 1) * safeSize;

        return dsl.selectFrom(TRANSACTIONS)
            .where(where)
            .orderBy(TRANSACTIONS.OCCURRED_AT.desc(), TRANSACTIONS.ID.desc())
            .limit(safeSize + 1)
            .offset(offset)
            .fetch();
    }

    /**
     * Owner-scoped lookup. {@link Optional#empty()} when the transaction does not exist OR belongs
     * to another user — caller MUST map both to 404 (D10).
     */
    public Optional<TransactionsRecord> findById(UUID id, UUID userId) {
        return Optional.ofNullable(
            dsl.selectFrom(TRANSACTIONS)
                .where(TRANSACTIONS.ID.eq(id))
                .and(TRANSACTIONS.USER_ID.eq(userId))
                .fetchOne());
    }

    /**
     * Owner-scoped UPDATE of the category. Returns the number of rows affected — 0 means the
     * transaction did not exist or belonged to another user. The {@code updated_at} parameter is
     * the authoritative value (the DB trigger {@code set_updated_at} also fires, but writing
     * explicitly keeps the semantics testable).
     */
    public int updateCategory(UUID id, UUID userId, UUID categoryId, OffsetDateTime now) {
        return dsl.update(TRANSACTIONS)
            .set(TRANSACTIONS.CATEGORY_ID, categoryId)
            .set(TRANSACTIONS.UPDATED_AT, now)
            .where(TRANSACTIONS.ID.eq(id))
            .and(TRANSACTIONS.USER_ID.eq(userId))
            .execute();
    }

    /**
     * Owner-scoped UPDATE of the merchant FK. Used by the feedback loop after the
     * {@code merchants} UPSERT decides which merchant owns this transaction.
     */
    public int assignMerchantId(UUID id, UUID userId, UUID merchantId) {
        return dsl.update(TRANSACTIONS)
            .set(TRANSACTIONS.MERCHANT_ID, merchantId)
            .where(TRANSACTIONS.ID.eq(id))
            .and(TRANSACTIONS.USER_ID.eq(userId))
            .execute();
    }
}
