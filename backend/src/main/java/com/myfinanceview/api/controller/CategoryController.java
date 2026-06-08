package com.myfinanceview.api.controller;

import com.myfinanceview.api.dto.CategoryDTO;
import com.myfinanceview.domain.category.CategoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for {@code GET /api/v1/categories}.
 *
 * <p>Returns system categories ({@code user_id IS NULL}) plus user-owned custom categories,
 * ordered by {@code display_name ASC} (mapped to {@code CategoryDTO.name} — design.md D6).
 * {@code parentId} is intentionally absent from the DTO (design.md D6 / B3 from adv-review).
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<List<CategoryDTO>> listCategories(
        @AuthenticationPrincipal UUID userId
    ) {
        return ResponseEntity.ok(categoryService.listForUser(userId));
    }
}
