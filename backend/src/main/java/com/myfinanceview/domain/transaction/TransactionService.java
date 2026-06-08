package com.myfinanceview.domain.transaction;

import com.myfinanceview.api.dto.PageDTO;
import com.myfinanceview.api.dto.TransactionDTO;
import com.myfinanceview.api.exception.NotFoundException;
import com.myfinanceview.domain.category.CategoryRepository;
import com.myfinanceview.domain.merchant.MerchantUpserter;
import com.myfinanceview.jooq.generated.tables.records.TransactionsRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the {@code /api/v1/transactions} endpoints.
 *
 * <p>Two operations:
 * <ul>
 *   <li>{@link #listForUser(UUID, Optional, List, int, int)} — paginated read with optional
 *       account/category filters. Page envelope follows design D4 ({@code pageSize+1} fetch,
 *       {@code hasMore} computed in-memory).</li>
 *   <li>{@link #updateCategory(UUID, UUID, UUID)} — idempotent PATCH that wires the merchant
 *       feedback loop (design D5) with anti-IDOR visibility guard on the new {@code categoryId}
 *       (design D10).</li>
 * </ul>
 *
 * <p>The PATCH writes inside a single {@code @Transactional} (REQUIRED, READ_COMMITTED via the
 * application-level default timeout of 5s) so a failure in any step rolls back the
 * {@code transactions} + {@code merchants} mutations together — no split-brain window.
 */
@Service
public class TransactionService {

    private final TransactionRepository txRepo;
    private final CategoryRepository categoryRepo;
    private final MerchantUpserter merchantUpserter;

    public TransactionService(
        TransactionRepository txRepo,
        CategoryRepository categoryRepo,
        MerchantUpserter merchantUpserter
    ) {
        this.txRepo = txRepo;
        this.categoryRepo = categoryRepo;
        this.merchantUpserter = merchantUpserter;
    }

    /**
     * Page-based listing for {@code GET /api/v1/transactions}.
     *
     * <p>The repository returns up to {@code pageSize+1} rows; this method computes
     * {@code hasMore = (received > pageSize)} and truncates to {@code pageSize} before mapping to
     * DTOs.
     */
    public PageDTO<TransactionDTO> listForUser(
        UUID userId,
        Optional<UUID> accountId,
        List<UUID> categoryIds,
        int page,
        int pageSize
    ) {
        List<TransactionsRecord> rows = txRepo.findPage(userId, accountId, categoryIds, page, pageSize);
        boolean hasMore = rows.size() > pageSize;
        List<TransactionsRecord> truncated = hasMore ? rows.subList(0, pageSize) : rows;
        List<TransactionDTO> dtos = truncated.stream()
            .map(TransactionMapper::fromRecord)
            .toList();
        return new PageDTO<>(dtos, page, pageSize, hasMore);
    }

    /**
     * Idempotent category PATCH with merchant feedback loop. Behaviour (D5 + D10):
     * <ol>
     *   <li><b>Visibility guard</b> — {@code newCategoryId} must be a system category or owned by
     *       the user. Otherwise → {@link NotFoundException} (404, no UUID echo).</li>
     *   <li>Load the transaction by {@code (txId, userId)}. If missing → 404.</li>
     *   <li>If {@code body.categoryId == tx.categoryId} → no-op idempotent return; the DTO is
     *       built from the current row state with no DB write.</li>
     *   <li>Otherwise inside {@code @Transactional}: UPDATE {@code transactions.category_id},
     *       apply merchant feedback (re-confirm / drift / first learning), re-assign
     *       {@code transactions.merchant_id} if a new merchant was created, then re-fetch and
     *       return the authoritative DTO.</li>
     * </ol>
     */
    @Transactional
    public TransactionDTO updateCategory(UUID userId, UUID txId, UUID newCategoryId) {
        // (1) Visibility guard for newCategoryId — anti-IDOR (D10). Run BEFORE idempotency
        // short-circuit so the security invariant always dominates the optimisation.
        if (categoryRepo.findByIdVisibleToUser(newCategoryId, userId).isEmpty()) {
            throw new NotFoundException("category not visible to user: " + newCategoryId);
        }

        // (2) Load the transaction. 404 collapses "does not exist" and "owned by another user".
        TransactionsRecord tx = txRepo.findById(txId, userId)
            .orElseThrow(() -> new NotFoundException("transaction not found: " + txId));

        // (3) Idempotency short-circuit: same category as current → no DB write, no merchant
        // mutation, no updated_at bump.
        if (Objects.equals(tx.getCategoryId(), newCategoryId)) {
            return TransactionMapper.fromRecord(tx);
        }

        // (4) Different category → mutate.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = txRepo.updateCategory(txId, userId, newCategoryId, now);
        if (updated != 1) {
            throw new IllegalStateException(
                "transactions.updateCategory affected " + updated + " rows, expected 1");
        }

        UUID currentMerchantId = tx.getMerchantId();
        UUID resultingMerchantId = merchantUpserter.applyFeedback(
            userId, currentMerchantId, tx.getDescription(), newCategoryId);

        if (resultingMerchantId != null && !Objects.equals(resultingMerchantId, currentMerchantId)) {
            int merchantAssigned = txRepo.assignMerchantId(txId, userId, resultingMerchantId);
            if (merchantAssigned != 1) {
                throw new IllegalStateException(
                    "transactions.assignMerchantId affected " + merchantAssigned + " rows, expected 1");
            }
        }

        // (5) Re-fetch for authoritative updated_at + merchant_id.
        TransactionsRecord fresh = txRepo.findById(txId, userId)
            .orElseThrow(() -> new IllegalStateException("transaction vanished after update: " + txId));
        return TransactionMapper.fromRecord(fresh);
    }
}
