-- ============================================================================
-- MyFinanceView — Merchants table + transactions.merchant_id FK
-- Database: Supabase PostgreSQL 15+
-- Schema: myfinance
-- Version: V005
--
-- Creates `myfinance.merchants` for the categorization feedback loop and adds
-- the nullable FK column `transactions.merchant_id` plus its partial index.
--
-- See openspec/changes/backend-mvp-readonly/design.md §D8 (schema) and §D5
-- (feedback loop semantics). RLS policies mirror `accounts_*` from V002 —
-- backend uses `service_role` (RLS-bypass) but the policies stay enabled as
-- defence in depth.
--
-- Additive-only. 362 historical transactions remain with merchant_id = NULL
-- until the user re-categorizes them via PATCH (Branch B of D5).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Table
-- ----------------------------------------------------------------------------
CREATE TABLE myfinance.merchants (
    id                 UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id            UUID         NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    display_name       TEXT         NOT NULL,
    raw_pattern        TEXT         NOT NULL,
    category_id        UUID         NOT NULL REFERENCES myfinance.categories(id),
    confidence         NUMERIC(3,2) NOT NULL DEFAULT 0.50
                          CHECK (confidence BETWEEN 0.00 AND 1.00),
    match_count        INT          NOT NULL DEFAULT 0
                          CHECK (match_count >= 0),
    last_confirmed_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (user_id, raw_pattern)
);

COMMENT ON TABLE  myfinance.merchants                   IS 'Per-user merchant patterns learned from PATCH feedback loop';
COMMENT ON COLUMN myfinance.merchants.raw_pattern       IS 'Output of MerchantNormalizer.normalize(description). Frozen per design.md D12.';
COMMENT ON COLUMN myfinance.merchants.confidence        IS 'Learned confidence for category mapping. Capped at 1.00.';
COMMENT ON COLUMN myfinance.merchants.last_confirmed_at IS 'NULL until first user confirmation; updated on every confirm/drift event.';

-- ----------------------------------------------------------------------------
-- 2. Index for FK from transactions.merchant_id (defined below) and for
--    repository queries by (user_id, category_id) joins.
-- ----------------------------------------------------------------------------
CREATE INDEX idx_merchants_user_category
    ON myfinance.merchants (user_id, category_id);

-- ----------------------------------------------------------------------------
-- 3. updated_at trigger (consistent with accounts/transactions in V001)
-- ----------------------------------------------------------------------------
CREATE TRIGGER set_merchants_updated_at
    BEFORE UPDATE ON myfinance.merchants
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

-- ----------------------------------------------------------------------------
-- 4. RLS — mirror accounts_* from V002 (owner-only CRUD).
--    Backend uses service_role and bypasses these; the policies remain as
--    defence in depth in case a non-service connection ever appears.
-- ----------------------------------------------------------------------------
ALTER TABLE myfinance.merchants ENABLE ROW LEVEL SECURITY;

CREATE POLICY "merchants_select_own"
    ON myfinance.merchants FOR SELECT
    TO authenticated
    USING (auth.uid() = user_id);

CREATE POLICY "merchants_insert_own"
    ON myfinance.merchants FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "merchants_update_own"
    ON myfinance.merchants FOR UPDATE
    TO authenticated
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "merchants_delete_own"
    ON myfinance.merchants FOR DELETE
    TO authenticated
    USING (auth.uid() = user_id);

-- ----------------------------------------------------------------------------
-- 5. transactions.merchant_id FK (nullable; ON DELETE SET NULL preserves the
--    transaction row when a merchant is later removed via merchant management UI).
-- ----------------------------------------------------------------------------
ALTER TABLE myfinance.transactions
    ADD COLUMN merchant_id UUID
        REFERENCES myfinance.merchants(id) ON DELETE SET NULL;

COMMENT ON COLUMN myfinance.transactions.merchant_id IS
    'FK to myfinance.merchants. NULL until user assigns category for a new merchant pattern.';

-- Partial index — most rows stay NULL until users re-categorize.
CREATE INDEX idx_transactions_merchant_id
    ON myfinance.transactions (merchant_id)
    WHERE merchant_id IS NOT NULL;
