package com.myfinanceview.domain.category;

import java.util.UUID;

/**
 * Stable reference to a category in the catalogue.
 *
 * <ul>
 *   <li>{@code id}   — {@code categories.id} (UUID primary key, stable across deploys)</li>
 *   <li>{@code name} — {@code categories.name} (English display label, e.g. "Dining Out").
 *       This is NOT the Spanish {@code display_name}; presentation layer is responsible
 *       for that translation.</li>
 * </ul>
 */
public record CategoryRef(UUID id, String name) {}
