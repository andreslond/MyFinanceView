package com.myfinanceview.domain.category;

import com.myfinanceview.api.dto.CategoryDTO;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Read-only service for {@code GET /api/v1/categories}. Returns system categories
 * ({@code user_id IS NULL}) merged with the authenticated user's custom categories.
 *
 * <p>Ordering: {@code display_name ASC} (enforced by the repository).
 */
@Service
public class CategoryService {

    private final CategoryRepository repo;

    public CategoryService(CategoryRepository repo) {
        this.repo = repo;
    }

    public List<CategoryDTO> listForUser(UUID userId) {
        return repo.findAllVisibleToUser(userId).stream()
            .map(CategoryMapper::fromRecord)
            .toList();
    }
}
