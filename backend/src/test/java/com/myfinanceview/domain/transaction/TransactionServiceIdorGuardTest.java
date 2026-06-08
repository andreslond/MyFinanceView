package com.myfinanceview.domain.transaction;

import com.myfinanceview.api.exception.NotFoundException;
import com.myfinanceview.jooq.generated.tables.records.CategoriesRecord;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.CATEGORIES;
import static com.myfinanceview.jooq.generated.Tables.TRANSACTIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Visibility-guard contract (tasks.md §6.10, spec.md scenario "PATCH no puede asignar categoryId
 * de otro usuario"): userA tries to PATCH her own transaction with a categoryId that belongs to
 * userB. The service MUST throw {@link NotFoundException} and leave both rows untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class TransactionServiceIdorGuardTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    @Test
    void shouldThrowNotFoundWhenCategoryBelongsToAnotherUserAndNotMutateAnything() {
        seedUser(USER_A);
        seedUser(USER_B);
        UUID catSystem = anySystemCategoryId();
        UUID catBPrivate = seedCustomCategory(USER_B, "B Custom", "B Custom ES");
        UUID accountA = seedAccount(USER_A, "checking");
        UUID txAId = seedTransaction(USER_A, accountA, catSystem, /*merchantId*/ null,
            "tx-A", offsetMinutesAgo(15));

        TransactionsRecord beforeTx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txAId)).fetchOne();
        CategoriesRecord beforeCat = dsl.selectFrom(CATEGORIES)
            .where(CATEGORIES.ID.eq(catBPrivate)).fetchOne();

        assertThatThrownBy(() -> service.updateCategory(USER_A, txAId, catBPrivate))
            .isInstanceOf(NotFoundException.class);

        TransactionsRecord afterTx = dsl.selectFrom(TRANSACTIONS)
            .where(TRANSACTIONS.ID.eq(txAId)).fetchOne();
        assertThat(afterTx.getCategoryId()).isEqualTo(beforeTx.getCategoryId());
        assertThat(afterTx.getUpdatedAt()).isEqualTo(beforeTx.getUpdatedAt());

        CategoriesRecord afterCat = dsl.selectFrom(CATEGORIES)
            .where(CATEGORIES.ID.eq(catBPrivate)).fetchOne();
        assertThat(afterCat.getName()).isEqualTo(beforeCat.getName());
        assertThat(afterCat.getDisplayName()).isEqualTo(beforeCat.getDisplayName());
        assertThat(afterCat.getUpdatedAt()).isEqualTo(beforeCat.getUpdatedAt());
    }

    @Test
    void shouldThrowNotFoundWhenCategoryDoesNotExist() {
        seedUser(USER_A);
        UUID catSystem = anySystemCategoryId();
        UUID accountA = seedAccount(USER_A, "checking");
        UUID txAId = seedTransaction(USER_A, accountA, catSystem, null, "tx-A", offsetMinutesAgo(15));

        UUID nonExistent = UUID.fromString("00000000-0000-0000-0000-00000000dead");

        assertThatThrownBy(() -> service.updateCategory(USER_A, txAId, nonExistent))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldThrowNotFoundWhenTransactionDoesNotExist() {
        seedUser(USER_A);
        UUID catSystem = anySystemCategoryId();
        UUID nonExistent = UUID.fromString("00000000-0000-0000-0000-00000000beef");

        assertThatThrownBy(() -> service.updateCategory(USER_A, nonExistent, catSystem))
            .isInstanceOf(NotFoundException.class);
    }
}
