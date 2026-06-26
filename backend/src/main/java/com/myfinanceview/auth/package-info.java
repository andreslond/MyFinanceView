/**
 * Auth glue between Spring Security's JWT machinery and the rest of the application.
 *
 * <p>The {@link com.myfinanceview.auth.UserIdJwtAuthenticationConverter} parses the
 * {@code sub} claim of a Supabase-issued JWT as a {@link java.util.UUID} and exposes it as the
 * Spring Security principal so controllers can pick it up with
 * {@code @AuthenticationPrincipal UUID userId}. Validation rules (issuer, audience, ES256-only,
 * exp) are wired in {@link com.myfinanceview.config.SecurityConfig}.
 *
 * <p>See {@code archive/openspec-legacy/changes/archive/2026-06-08-backend-mvp-readonly/design.md} D1 for the broader auth
 * strategy.
 */
package com.myfinanceview.auth;
