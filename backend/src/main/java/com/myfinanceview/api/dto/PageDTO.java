package com.myfinanceview.api.dto;

import java.util.List;

/**
 * Generic page envelope for cursor-less, page-based pagination — see design.md D4.
 *
 * <p>{@code hasMore} is computed by the service requesting {@code pageSize + 1} rows and truncating.
 * Shape mirrors {@code Page<T>} from {@code frontend/src/services/types.ts} so the eventual
 * frontend swap is a body replacement, not a refactor.
 *
 * @param <T> row type (e.g. {@link TransactionDTO}).
 */
public record PageDTO<T>(
    List<T> rows,
    int page,
    int pageSize,
    boolean hasMore
) {}
