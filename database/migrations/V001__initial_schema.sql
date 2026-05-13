-- ============================================================================
-- MyFinanceView — Initial Schema
-- Database: Supabase PostgreSQL 15+
-- Schema: myfinance
-- Version: V001
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 0. Extensions & Schema
-- ----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE SCHEMA IF NOT EXISTS myfinance;

-- ----------------------------------------------------------------------------
-- 1. Custom ENUM Types (in myfinance schema)
-- ----------------------------------------------------------------------------

CREATE TYPE myfinance.account_type AS ENUM (
    'checking',
    'savings',
    'credit_card'
);

CREATE TYPE myfinance.transaction_type AS ENUM (
    'credit_card_purchase',
    'debit_purchase',
    'credit_card_payment',
    'incoming_transfer',
    'outgoing_transfer',
    'incoming_payment'
);

CREATE TYPE myfinance.transaction_source AS ENUM (
    'gmail',
    'manual',
    'api'
);

CREATE TYPE myfinance.category_type AS ENUM (
    'expense',
    'income'
);

-- ----------------------------------------------------------------------------
-- 2. Tables
-- ----------------------------------------------------------------------------

-- 2.1 banks — system table, shared across all users
CREATE TABLE myfinance.banks (
    id         UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    name       TEXT        NOT NULL,
    code       TEXT        UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE  myfinance.banks      IS 'System table: supported banks';
COMMENT ON COLUMN myfinance.banks.code IS 'Short code for email-parsing bank identification';

-- 2.2 user_settings — one row per user
CREATE TABLE myfinance.user_settings (
    id            UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id       UUID        NOT NULL UNIQUE REFERENCES auth.users(id) ON DELETE CASCADE,
    base_currency TEXT        NOT NULL DEFAULT 'COP',
    timezone      TEXT        NOT NULL DEFAULT 'America/Bogota',
    locale        TEXT        NOT NULL DEFAULT 'es-CO',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE myfinance.user_settings IS 'Per-user configuration: base currency, timezone, locale';

-- 2.3 categories — system defaults + user custom
CREATE TABLE myfinance.categories (
    id         UUID                    PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID                    REFERENCES auth.users(id) ON DELETE CASCADE,
    name       TEXT                    NOT NULL,
    type       myfinance.category_type NOT NULL,
    icon       TEXT,
    color      TEXT,
    is_active  BOOLEAN                 NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ             NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ             NOT NULL DEFAULT now()
);

COMMENT ON TABLE  myfinance.categories         IS 'Transaction categories. user_id=NULL means system default';
COMMENT ON COLUMN myfinance.categories.user_id IS 'NULL for system-wide defaults; set for user-created categories';

-- 2.4 accounts
CREATE TABLE myfinance.accounts (
    id         UUID                    PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID                    NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    bank_id    UUID                    NOT NULL REFERENCES myfinance.banks(id) ON DELETE RESTRICT,
    type       myfinance.account_type  NOT NULL,
    currency   TEXT                    NOT NULL,
    last4      TEXT,
    nickname   TEXT,
    active     BOOLEAN                 NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ             NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ             NOT NULL DEFAULT now()
);

COMMENT ON TABLE  myfinance.accounts          IS 'User bank accounts and credit cards';
COMMENT ON COLUMN myfinance.accounts.currency IS 'ISO 4217 currency code';
COMMENT ON COLUMN myfinance.accounts.last4    IS 'Last 4 digits for display identification';

-- 2.5 transactions
CREATE TABLE myfinance.transactions (
    id                   UUID                        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id              UUID                        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    account_id           UUID                        NOT NULL REFERENCES myfinance.accounts(id) ON DELETE RESTRICT,
    category_id          UUID                        REFERENCES myfinance.categories(id) ON DELETE SET NULL,
    type                 myfinance.transaction_type  NOT NULL,
    amount               NUMERIC(18,2)               NOT NULL CHECK (amount >= 0),
    currency             TEXT                        NOT NULL,
    amount_base_currency NUMERIC(18,2)               NOT NULL CHECK (amount_base_currency >= 0),
    description          TEXT,
    notes                TEXT,
    occurred_at          TIMESTAMPTZ                 NOT NULL,
    source               myfinance.transaction_source NOT NULL DEFAULT 'gmail',
    external_id          TEXT,
    raw_payload          JSONB,
    created_at           TIMESTAMPTZ                 NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ                 NOT NULL DEFAULT now()
);

COMMENT ON TABLE  myfinance.transactions                      IS 'Financial transactions';
COMMENT ON COLUMN myfinance.transactions.amount               IS 'Amount in original currency. Always positive; type determines direction.';
COMMENT ON COLUMN myfinance.transactions.amount_base_currency IS 'Amount converted to user base currency at ingestion time';
COMMENT ON COLUMN myfinance.transactions.external_id          IS 'Email message_id or external ref for idempotent ingestion';
COMMENT ON COLUMN myfinance.transactions.raw_payload          IS 'Original email body or API payload as JSONB';

-- 2.6 budgets
CREATE TABLE myfinance.budgets (
    id           UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id      UUID          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    month        INT           NOT NULL CHECK (month BETWEEN 1 AND 12),
    year         INT           NOT NULL CHECK (year BETWEEN 2020 AND 2100),
    total_budget NUMERIC(18,2) NOT NULL CHECK (total_budget >= 0),
    currency     TEXT          NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE myfinance.budgets IS 'Monthly budget targets per user';

-- 2.7 budget_categories — per-category allocations within a budget
CREATE TABLE myfinance.budget_categories (
    id               UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id          UUID          NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    budget_id        UUID          NOT NULL REFERENCES myfinance.budgets(id) ON DELETE CASCADE,
    category_id      UUID          NOT NULL REFERENCES myfinance.categories(id) ON DELETE RESTRICT,
    allocated_amount NUMERIC(18,2) NOT NULL CHECK (allocated_amount >= 0),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  myfinance.budget_categories         IS 'Per-category budget allocations within a monthly budget';
COMMENT ON COLUMN myfinance.budget_categories.user_id IS 'Denormalized from budgets.user_id for efficient RLS';

-- 2.8 exchange_rates — system table
CREATE TABLE myfinance.exchange_rates (
    id              UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    base_currency   TEXT          NOT NULL,
    target_currency TEXT          NOT NULL,
    rate            NUMERIC(18,8) NOT NULL CHECK (rate > 0),
    effective_date  DATE          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

COMMENT ON TABLE  myfinance.exchange_rates                IS 'Daily exchange rates for currency conversion';
COMMENT ON COLUMN myfinance.exchange_rates.rate           IS 'Units of target_currency per 1 unit of base_currency';
COMMENT ON COLUMN myfinance.exchange_rates.effective_date IS 'Date this rate is valid for';

-- ----------------------------------------------------------------------------
-- 3. Unique Constraints
-- ----------------------------------------------------------------------------

ALTER TABLE myfinance.budgets
    ADD CONSTRAINT uq_budgets_user_month_year
    UNIQUE (user_id, month, year);

ALTER TABLE myfinance.budget_categories
    ADD CONSTRAINT uq_budget_categories_budget_category
    UNIQUE (budget_id, category_id);

ALTER TABLE myfinance.exchange_rates
    ADD CONSTRAINT uq_exchange_rates_pair_date
    UNIQUE (base_currency, target_currency, effective_date);

-- Partial unique index: only enforce uniqueness on non-NULL external_id
CREATE UNIQUE INDEX uq_transactions_external_id
    ON myfinance.transactions (external_id)
    WHERE external_id IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 4. Performance Indexes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_accounts_user_id
    ON myfinance.accounts (user_id);

CREATE INDEX idx_categories_user_id
    ON myfinance.categories (user_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX idx_transactions_user_occurred
    ON myfinance.transactions (user_id, occurred_at DESC);

CREATE INDEX idx_transactions_user_type
    ON myfinance.transactions (user_id, type);

CREATE INDEX idx_transactions_account
    ON myfinance.transactions (account_id);

CREATE INDEX idx_transactions_category
    ON myfinance.transactions (category_id)
    WHERE category_id IS NOT NULL;

CREATE INDEX idx_budgets_user_year_month
    ON myfinance.budgets (user_id, year DESC, month DESC);

CREATE INDEX idx_budget_categories_budget
    ON myfinance.budget_categories (budget_id);

CREATE INDEX idx_budget_categories_user
    ON myfinance.budget_categories (user_id);

CREATE INDEX idx_exchange_rates_lookup
    ON myfinance.exchange_rates (base_currency, target_currency, effective_date DESC);

-- ----------------------------------------------------------------------------
-- 5. Triggers: auto-update updated_at
-- ----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION myfinance.trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.user_settings
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.categories
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.accounts
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.transactions
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.budgets
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();

CREATE TRIGGER set_updated_at BEFORE UPDATE ON myfinance.budget_categories
    FOR EACH ROW EXECUTE FUNCTION myfinance.trigger_set_updated_at();
