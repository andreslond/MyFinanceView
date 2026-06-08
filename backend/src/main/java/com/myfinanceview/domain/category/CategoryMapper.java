package com.myfinanceview.domain.category;

import com.myfinanceview.api.dto.CategoryDTO;
import com.myfinanceview.jooq.generated.tables.records.CategoriesRecord;

import java.util.UUID;

/**
 * Pure row-to-DTO mapper for categories. Static-only utility — instantiation is forbidden.
 *
 * <p><b>Schema-rename here</b> (design D6): the input parameter {@code displayName}
 * ({@code categories.display_name}, ES, NOT NULL post-V004) becomes {@link CategoryDTO#name} on
 * the wire. The English {@code categories.name} is the stable internal key and is NOT exposed.
 *
 * <p><b>Field drop here</b> (design D6 / adv-review B3): the schema has no {@code parent_id}
 * column and the DTO has no {@code parentId} field — the mapper accepts no such parameter, which
 * makes the drop compile-time-enforced rather than convention.
 *
 * <p>{@code createdAt} / {@code updatedAt} are deliberately absent from {@link CategoryDTO} per
 * the api-spec.yml contract (categories are a near-static taxonomy; lifecycle timestamps would
 * be noise on the wire). The columns still exist in the DB and the repository may use them for
 * audit purposes — they just don't make it to JSON.
 */
public final class CategoryMapper {

    private CategoryMapper() {
        throw new AssertionError("static utility — do not instantiate");
    }

    /**
     * Map a row tuple from {@code myfinance.categories} to its wire DTO.
     *
     * @param id           {@code categories.id}.
     * @param displayName  {@code categories.display_name} (Spanish label, NOT NULL post-V004) —
     *                     renamed to {@link CategoryDTO#name} on the wire.
     * @param type         already stringified enum literal ({@code "expense"} or {@code "income"}).
     * @param color        {@code categories.color} (nullable hex code).
     * @param icon         {@code categories.icon} (nullable icon name).
     */
    public static CategoryDTO fromRow(
        UUID id,
        String displayName,
        String type,
        String color,
        String icon
    ) {
        return new CategoryDTO(id, displayName, type, color, icon);
    }

    /**
     * Convenience overload for jOOQ-generated records. Delegates to {@link #fromRow} so the
     * {@code display_name → name} rename invariant lives in exactly one place.
     */
    public static CategoryDTO fromRecord(CategoriesRecord rec) {
        return fromRow(
            rec.getId(),
            rec.getDisplayName(),
            rec.getType().getLiteral(),
            rec.getColor(),
            rec.getIcon()
        );
    }
}
