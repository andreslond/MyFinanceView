package com.myfinanceview.domain.account;

import com.myfinanceview.jooq.generated.tables.records.AccountsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.ACCOUNTS;

/**
 * Read access for {@code myfinance.accounts}, scoped to the authenticated user.
 *
 * <p><b>Isolation invariant</b> (design.md D2): every method takes {@code userId} and pins the
 * query with {@code WHERE user_id = ?}. There is no method without the filter. The backend
 * connects with {@code service_role} (RLS-bypass) so this Java-level filter is the only thing
 * keeping cross-user data invisible — proven by {@link com.myfinanceview.domain.transaction
 * .TransactionRepositoryIsolationTest} for the sister table.
 */
@Repository
public class AccountRepository {

    private final DSLContext dsl;

    public AccountRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Return the user's accounts ordered by {@code nickname} (stable alphabetical for the UI).
     */
    public List<AccountsRecord> findAllByUserId(UUID userId) {
        return dsl.selectFrom(ACCOUNTS)
            .where(ACCOUNTS.USER_ID.eq(userId))
            .orderBy(ACCOUNTS.NICKNAME.asc())
            .fetch();
    }
}
