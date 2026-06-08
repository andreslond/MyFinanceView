package com.myfinanceview.domain.account;

import com.myfinanceview.api.dto.AccountDTO;
import com.myfinanceview.config.PostgresJooqIntegrationTestBase;
import com.myfinanceview.jooq.generated.enums.AccountType;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.myfinanceview.jooq.generated.Tables.ACCOUNTS;
import static com.myfinanceview.jooq.generated.Tables.BANKS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for {@link AccountService} against a real Spring context + Testcontainers Postgres.
 *
 * <p>Doubles as the validation that {@link PostgresJooqIntegrationTestBase} wires a real jOOQ
 * {@link DSLContext} and that {@code @Transactional} auto-rollback works.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class AccountServiceTest extends PostgresJooqIntegrationTestBase {

    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired DSLContext dsl;
    @Autowired AccountService service;

    @Test
    void shouldListAccountsForUser() {
        // Seed user + bank + 2 accounts; the second account isolation-tests user filtering.
        UUID userB = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
        dsl.execute("INSERT INTO auth.users(id) VALUES (?), (?) ON CONFLICT DO NOTHING", USER_A, userB);

        UUID bankId = dsl.select(BANKS.ID).from(BANKS).limit(1).fetchOne(BANKS.ID);

        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        dsl.insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, accountA)
            .set(ACCOUNTS.USER_ID, USER_A)
            .set(ACCOUNTS.BANK_ID, bankId)
            .set(ACCOUNTS.TYPE, AccountType.checking)
            .set(ACCOUNTS.CURRENCY, "COP")
            .set(ACCOUNTS.NICKNAME, "Davivienda Signature")
            .execute();
        dsl.insertInto(ACCOUNTS)
            .set(ACCOUNTS.ID, accountB)
            .set(ACCOUNTS.USER_ID, userB)
            .set(ACCOUNTS.BANK_ID, bankId)
            .set(ACCOUNTS.TYPE, AccountType.checking)
            .set(ACCOUNTS.CURRENCY, "COP")
            .set(ACCOUNTS.NICKNAME, "Should not appear")
            .execute();

        List<AccountDTO> result = service.listForUser(USER_A);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Davivienda Signature");
        assertThat(result.get(0).type()).isEqualTo("checking");
        assertThat(result.get(0).currency()).isEqualTo("COP");
    }
}
