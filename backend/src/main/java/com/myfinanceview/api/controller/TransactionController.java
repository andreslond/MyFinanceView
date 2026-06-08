package com.myfinanceview.api.controller;

import com.myfinanceview.api.dto.PageDTO;
import com.myfinanceview.api.dto.TransactionDTO;
import com.myfinanceview.api.dto.UpdateCategoryRequest;
import com.myfinanceview.domain.transaction.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for transaction endpoints.
 *
 * <p>{@code GET /api/v1/transactions} — paginated listing with optional filters.
 * <p>{@code PATCH /api/v1/transactions/{id}/category} — idempotent category re-assignment with
 * merchant feedback loop (design.md D5, anti-IDOR D10).
 *
 * <p>{@link Validated} at class level activates Bean Validation on {@code @RequestParam}
 * constraints ({@code @Min}, {@code @Max}) — Spring routes constraint violations to
 * {@code ProblemDetailAdvice} which strips the offending value from the response (zero-echo D11).
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Paginated list of transactions for the authenticated user.
     *
     * <p>Defaults: {@code page=1}, {@code pageSize=25}. Max pageSize is 100. Results ordered
     * {@code occurred_at DESC, id DESC} (tie-break for n8n batch imports).
     *
     * @param accountId  optional filter; cross-user UUID yields empty result set (not an error)
     * @param categoryIds comma-separated category UUIDs; cross-user yields empty result set
     * @param page        1-based page index
     * @param pageSize    number of rows per page (1–100)
     */
    @GetMapping
    public ResponseEntity<PageDTO<TransactionDTO>> listTransactions(
        @AuthenticationPrincipal UUID userId,
        @RequestParam(required = false) Optional<UUID> accountId,
        @RequestParam(required = false) List<UUID> categoryIds,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize
    ) {
        List<UUID> cats = (categoryIds == null) ? List.of() : categoryIds;
        return ResponseEntity.ok(transactionService.listForUser(userId, accountId, cats, page, pageSize));
    }

    /**
     * Re-assigns the category of a single transaction (idempotent).
     *
     * <p>Triggers the merchant feedback loop inside a single Postgres transaction (design.md D5).
     * Anti-IDOR: {@code body.categoryId} not visible to the user → 404 (not 403, design.md D10).
     */
    @PatchMapping("/{id}/category")
    public ResponseEntity<TransactionDTO> updateCategory(
        @AuthenticationPrincipal UUID userId,
        @PathVariable UUID id,
        @Valid @RequestBody UpdateCategoryRequest body
    ) {
        return ResponseEntity.ok(transactionService.updateCategory(userId, id, body.categoryId()));
    }
}
