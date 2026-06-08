package com.myfinanceview.domain.transaction;

import com.myfinanceview.api.dto.PageDTO;
import com.myfinanceview.api.dto.TransactionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pagination envelope contract (tasks.md §6.12, spec.md scenario "Paginación última página" +
 * "Página más allá del final"): the service computes {@code hasMore} from {@code rows.size() >
 * pageSize}, truncates to {@code pageSize}, and returns the page envelope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles({"test", "service"})
@Transactional
class TransactionServicePaginationTest extends TransactionServiceTestBase {

    @Autowired TransactionService service;

    @Test
    void shouldPaginateOver100Rows() {
        seedUser(USER_A);
        UUID accountA = seedAccount(USER_A, "checking");
        UUID catSystem = anySystemCategoryId();
        // 100 rows so 4 full pages of 25 + 0 on page 5.
        for (int i = 0; i < 100; i++) {
            seedTransaction(USER_A, accountA, catSystem, null, "tx-" + i, offsetMinutesAgo(i));
        }

        PageDTO<TransactionDTO> page1 = service.listForUser(USER_A, Optional.empty(), List.of(), 1, 25);
        assertThat(page1.rows()).hasSize(25);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.pageSize()).isEqualTo(25);
        assertThat(page1.hasMore()).isTrue();

        PageDTO<TransactionDTO> page4 = service.listForUser(USER_A, Optional.empty(), List.of(), 4, 25);
        assertThat(page4.rows()).hasSize(25);
        assertThat(page4.hasMore()).isFalse();

        PageDTO<TransactionDTO> page5 = service.listForUser(USER_A, Optional.empty(), List.of(), 5, 25);
        assertThat(page5.rows()).isEmpty();
        assertThat(page5.hasMore()).isFalse();
    }
}
