package com.myfinanceview.domain.category;

import com.myfinanceview.api.dto.CategoryDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link CategoryMapper}. Pins the two design D6 invariants:
 * <ol>
 *   <li>{@code categories.display_name} surfaces as {@link CategoryDTO#name} on the wire.</li>
 *   <li>{@code CategoryDTO} has no {@code parentId} (adv-review B3) — enforced at compile time
 *       by the record shape and re-asserted reflectively here so anyone trying to add it back
 *       fails CI.</li>
 * </ol>
 */
class CategoryMapperTest {

    @Test
    void shouldRenameDisplayNameColumnToNameOnDtoWhenMapping() {
        UUID id = UUID.randomUUID();
        CategoryDTO dto = CategoryMapper.fromRow(
            id, "Restaurantes y Cafés", "expense", "#FF6B6B", "utensils");

        assertThat(dto.name()).isEqualTo("Restaurantes y Cafés");
        assertThat(dto.id()).isEqualTo(id);
    }

    @Test
    void shouldExposeExpenseTypeAsLowercaseLiteralWhenMapping() {
        CategoryDTO dto = CategoryMapper.fromRow(
            UUID.randomUUID(), "Mercado y Supermercado", "expense", null, null);

        assertThat(dto.type()).isEqualTo("expense");
    }

    @Test
    void shouldExposeIncomeTypeAsLowercaseLiteralWhenMapping() {
        CategoryDTO dto = CategoryMapper.fromRow(
            UUID.randomUUID(), "Salario", "income", null, null);

        assertThat(dto.type()).isEqualTo("income");
    }

    @Test
    void shouldKeepColorAndIconNullWhenSourceColumnsAreNull() {
        CategoryDTO dto = CategoryMapper.fromRow(
            UUID.randomUUID(), "Otros Gastos", "expense", null, null);

        assertThat(dto.color()).isNull();
        assertThat(dto.icon()).isNull();
    }

    @Test
    void shouldNotHaveParentIdComponentInCategoryDtoRecord() {
        // adv-review B3 / design D6: parentId was MVP-frontend speculation with no schema backing.
        // Reflectively assert no such record component exists so any future re-introduction is
        // caught immediately, not at integration time.
        assertThat(Arrays.stream(CategoryDTO.class.getRecordComponents())
                .map(RecordComponent::getName))
            .doesNotContain("parentId");
    }
}
