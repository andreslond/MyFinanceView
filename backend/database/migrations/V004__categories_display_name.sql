-- ============================================================================
-- MyFinanceView — Categories display_name (Spanish)
-- Database: Supabase PostgreSQL 15+
-- Schema: myfinance
-- Version: V004
--
-- Adds `display_name` to `myfinance.categories` and backfills the 19 system
-- categories (user_id IS NULL) with their canonical Spanish labels.
--
-- See archive/openspec-legacy/changes/archive/2026-06-08-backend-mvp-readonly/design.md §D8 and SPEC.md §4.
--
-- Mapping rationale: V003 seeded the system categories using English labels
-- in the `name` column (e.g. `'Dining Out'`, not `'restaurants_and_cafes'`).
-- This migration keeps `name` as the stable internal key (jOOQ codegen depends
-- on it) and adds `display_name` as the user-facing Spanish label. The mapping
-- here is the source of truth — if V003 internal keys ever change, this file
-- MUST be updated in lockstep.
--
-- Additive-only; safe to apply against the live Supabase project as long as
-- no user-owned categories exist yet (verified: data-model.md §2 confirms
-- 0 user-owned categories at 2026-05-11). The defensive fallback
-- `UPDATE ... WHERE display_name IS NULL` covers any user-owned rows that
-- might appear between local testing and remote application.
-- ============================================================================

ALTER TABLE myfinance.categories
    ADD COLUMN display_name TEXT;

COMMENT ON COLUMN myfinance.categories.display_name IS
    'User-facing label in Spanish (es-CO). NOT NULL post-V004. Internal key remains in `name`.';

-- ----------------------------------------------------------------------------
-- Backfill: 19 system categories (user_id IS NULL) — see V003 for the seed.
-- Order matches V003 for easy diffing.
-- ----------------------------------------------------------------------------

-- Expense (14)
UPDATE myfinance.categories SET display_name = 'Hogar'                       WHERE name = 'Housing'          AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Transporte'                  WHERE name = 'Transportation'   AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Mercado y Supermercado'      WHERE name = 'Food & Groceries' AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Restaurantes y Cafés'        WHERE name = 'Dining Out'       AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Servicios Públicos'          WHERE name = 'Utilities'        AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Salud'                       WHERE name = 'Healthcare'       AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Seguros'                     WHERE name = 'Insurance'        AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Entretenimiento'             WHERE name = 'Entertainment'    AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Compras'                     WHERE name = 'Shopping'         AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Educación'                   WHERE name = 'Education'        AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Cuidado Personal'            WHERE name = 'Personal Care'    AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Suscripciones'               WHERE name = 'Subscriptions'    AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Pago de Deudas'              WHERE name = 'Debt Payments'    AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Otros Gastos'                WHERE name = 'Other Expense'    AND user_id IS NULL;

-- Income (5)
UPDATE myfinance.categories SET display_name = 'Salario'                     WHERE name = 'Salary'           AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Trabajo Freelance'           WHERE name = 'Freelance'        AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Inversiones'                 WHERE name = 'Investments'      AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Transferencias Recibidas'    WHERE name = 'Transfers In'     AND user_id IS NULL;
UPDATE myfinance.categories SET display_name = 'Otros Ingresos'              WHERE name = 'Other Income'     AND user_id IS NULL;

-- ----------------------------------------------------------------------------
-- Defensive fallback: any row (system or user-owned) still without a
-- display_name gets the existing `name` echoed. Covers:
--   - hypothetical user-owned categories created between adversarial review
--     and remote application of this migration
--   - any system seed row that the per-key UPDATEs above missed (would only
--     happen if V003 drifts out of sync with this file — adversarial signal)
-- ----------------------------------------------------------------------------
UPDATE myfinance.categories
   SET display_name = name
 WHERE display_name IS NULL;

-- ----------------------------------------------------------------------------
-- Enforce NOT NULL going forward (every INSERT must supply display_name).
-- ----------------------------------------------------------------------------
ALTER TABLE myfinance.categories
    ALTER COLUMN display_name SET NOT NULL;
