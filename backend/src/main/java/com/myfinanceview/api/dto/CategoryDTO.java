package com.myfinanceview.api.dto;

import java.util.UUID;

/**
 * Wire representation of a transaction category. The same DTO carries both system categories
 * ({@code user_id IS NULL}) and the authenticated user's custom categories — they are merged
 * server-side by {@link com.myfinanceview.domain.category.CategoryRepository#findAllVisibleToUser}.
 *
 * <p><b>Schema-rename in mapper</b> (design D6): the DB column {@code categories.display_name}
 * (Spanish, NOT NULL post-V004) surfaces here as {@code name}. The English {@code categories.name}
 * is the stable internal key and is NOT exposed on the wire.
 *
 * <p><b>Field drop</b> (design D6 / adv-review B3): the MVP frontend invented {@code parentId};
 * the schema has no {@code parent_id} column and none is planned in this change. The DTO does
 * NOT include {@code parentId}.
 *
 * <p><b>Field drop</b> (api-spec.yml divergence from tasks.md §4.3 — api-spec.yml wins per the
 * §4 brief): {@code createdAt} / {@code updatedAt} are NOT exposed for categories. The frontend
 * does not need lifecycle timestamps for a near-static taxonomy.
 */
public record CategoryDTO(
    UUID id,
    String name,
    String type,
    String color,
    String icon
) {}
