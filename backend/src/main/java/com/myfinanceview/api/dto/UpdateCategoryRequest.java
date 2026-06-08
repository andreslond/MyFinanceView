package com.myfinanceview.api.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Body for {@code PATCH /api/v1/transactions/{id}/category}.
 *
 * <p>{@code categoryId} must reference a category visible to the authenticated user — either a
 * system category ({@code user_id IS NULL}) or one of the user's own custom categories. The
 * visibility guard lives in the service layer (design D10, anti-IDOR); a non-visible UUID yields
 * 404 without echoing the offending value.
 */
public record UpdateCategoryRequest(
    @NotNull UUID categoryId
) {}
