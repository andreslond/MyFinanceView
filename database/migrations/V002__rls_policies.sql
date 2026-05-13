-- ============================================================================
-- MyFinanceView — Row Level Security Policies
-- Database: Supabase PostgreSQL 15+
-- Schema: myfinance
-- Version: V002
--
-- Strategy:
--   - All tables have RLS enabled
--   - User-owned tables: CRUD scoped to auth.uid() = user_id
--   - System tables (banks, exchange_rates): read-only for authenticated
--   - Categories: hybrid — system defaults readable by all + user custom
--   - Service role bypasses RLS (used by Spring Boot backend)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Enable RLS on all tables
-- ----------------------------------------------------------------------------

ALTER TABLE myfinance.banks              ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.user_settings      ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.categories         ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.accounts           ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.transactions       ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.budgets            ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.budget_categories  ENABLE ROW LEVEL SECURITY;
ALTER TABLE myfinance.exchange_rates     ENABLE ROW LEVEL SECURITY;

-- ----------------------------------------------------------------------------
-- banks — read-only for authenticated users
-- Write operations only through service_role (backend / migrations)
-- ----------------------------------------------------------------------------

CREATE POLICY "banks_select_authenticated"
    ON myfinance.banks FOR SELECT
    TO authenticated
    USING (true);

-- ----------------------------------------------------------------------------
-- user_settings — full CRUD scoped to own user
-- ----------------------------------------------------------------------------

CREATE POLICY "user_settings_select_own"
    ON myfinance.user_settings FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "user_settings_insert_own"
    ON myfinance.user_settings FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "user_settings_update_own"
    ON myfinance.user_settings FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "user_settings_delete_own"
    ON myfinance.user_settings FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- categories — system categories readable by all; user custom scoped to owner
-- Users cannot modify system categories (user_id IS NULL)
-- ----------------------------------------------------------------------------

CREATE POLICY "categories_select_system_and_own"
    ON myfinance.categories FOR SELECT
    TO authenticated
    USING (user_id IS NULL OR auth.uid() = user_id);

CREATE POLICY "categories_insert_own"
    ON myfinance.categories FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "categories_update_own"
    ON myfinance.categories FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "categories_delete_own"
    ON myfinance.categories FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- accounts — full CRUD scoped to own user
-- ----------------------------------------------------------------------------

CREATE POLICY "accounts_select_own"
    ON myfinance.accounts FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "accounts_insert_own"
    ON myfinance.accounts FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "accounts_update_own"
    ON myfinance.accounts FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "accounts_delete_own"
    ON myfinance.accounts FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- transactions — full CRUD scoped to own user
-- ----------------------------------------------------------------------------

CREATE POLICY "transactions_select_own"
    ON myfinance.transactions FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "transactions_insert_own"
    ON myfinance.transactions FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "transactions_update_own"
    ON myfinance.transactions FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "transactions_delete_own"
    ON myfinance.transactions FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- budgets — full CRUD scoped to own user
-- ----------------------------------------------------------------------------

CREATE POLICY "budgets_select_own"
    ON myfinance.budgets FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "budgets_insert_own"
    ON myfinance.budgets FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "budgets_update_own"
    ON myfinance.budgets FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "budgets_delete_own"
    ON myfinance.budgets FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- budget_categories — full CRUD scoped to own user
-- user_id is denormalized from budgets for RLS performance
-- ----------------------------------------------------------------------------

CREATE POLICY "budget_categories_select_own"
    ON myfinance.budget_categories FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "budget_categories_insert_own"
    ON myfinance.budget_categories FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "budget_categories_update_own"
    ON myfinance.budget_categories FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "budget_categories_delete_own"
    ON myfinance.budget_categories FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- exchange_rates — read-only for authenticated users
-- Write operations only through service_role (backend / migrations)
-- ----------------------------------------------------------------------------

CREATE POLICY "exchange_rates_select_authenticated"
    ON myfinance.exchange_rates FOR SELECT
    TO authenticated
    USING (true);
