package com.myfinanceview.domain.category;

import com.myfinanceview.jooq.generated.tables.records.CategoriesRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.CATEGORIES;

/**
 * Access to {@code myfinance.categories}. Two visibility rules:
 * <ul>
 *   <li>System categories: {@code user_id IS NULL} — visible to every authenticated user.</li>
 *   <li>Custom categories: {@code user_id = <authenticated>} — visible only to their owner.</li>
 * </ul>
 *
 * <p><b>Visibility guard</b> (design.md D10 / anti-IDOR): {@link #findByIdVisibleToUser(UUID, UUID)}
 * is the SELECT executed before {@code UPDATE transactions.category_id = ?} in
 * {@code TransactionService}. Without it, an attacker could distinguish another user's category by
 * inducing an FK violation (500) vs success (200). The repository returns {@link Optional#empty()}
 * for any UUID that is neither system nor owned — the service then maps to 404 without
 * differentiating.
 */
@Repository
public class CategoryRepository {

    private final DSLContext dsl;

    public CategoryRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Return all categories visible to the user (system + own), ordered by Spanish display name.
     */
    public List<CategoriesRecord> findAllVisibleToUser(UUID userId) {
        return dsl.selectFrom(CATEGORIES)
            .where(CATEGORIES.USER_ID.isNull().or(CATEGORIES.USER_ID.eq(userId)))
            .orderBy(CATEGORIES.DISPLAY_NAME.asc())
            .fetch();
    }

    /**
     * Visibility guard for PATCH (D10). Returns the category iff:
     * <ul>
     *   <li>it exists with {@code user_id IS NULL} (system default), or</li>
     *   <li>it exists with {@code user_id = userId} (caller-owned).</li>
     * </ul>
     * Otherwise returns {@link Optional#empty()} — caller MUST map to 404 (do NOT differentiate
     * "exists but belongs to another user" vs "does not exist": that distinction leaks).
     */
    public Optional<CategoriesRecord> findByIdVisibleToUser(UUID categoryId, UUID userId) {
        return Optional.ofNullable(
            dsl.selectFrom(CATEGORIES)
                .where(CATEGORIES.ID.eq(categoryId))
                .and(CATEGORIES.USER_ID.isNull().or(CATEGORIES.USER_ID.eq(userId)))
                .fetchOne());
    }
}
