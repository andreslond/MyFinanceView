-- seed.sql — smoke-test fixture for scripts/backup/test-smoke.sh
--
-- Seeds the minimum table set required by verify-queries.sql probes,
-- with row counts that EXCEED the thresholds:
--   transactions  >= 300  (this file inserts 400)
--   accounts      >= 1    (inserts 3)
--   categories    >= 19   (inserts 20)
--   auth.users    >= 1    (inserts 1)
--
-- Deliberately MINIMAL DDL — uses TEXT/UUID columns only, no custom types,
-- so it runs on a stock postgres:17 container without Supabase extensions.
-- The stub FKs (auth.users → myfinance.accounts → myfinance.transactions)
-- preserve restore-order semantics that verify-restore.sh exercises.

-- ---------------------------------------------------------------------------
-- auth schema stub (mirrors what verify-restore.sh pre-creates)
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS auth;
CREATE TABLE IF NOT EXISTS auth.users (
  id uuid PRIMARY KEY
);

INSERT INTO auth.users (id)
VALUES ('00000000-0000-0000-0000-000000000001')
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------
-- myfinance schema
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS myfinance;

-- categories (minimal — no enums, uses TEXT)
CREATE TABLE IF NOT EXISTS myfinance.categories (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     uuid REFERENCES auth.users(id),
  name        text NOT NULL UNIQUE,
  type        text NOT NULL DEFAULT 'expense',
  icon        text,
  color       text,
  is_active   boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO myfinance.categories (name, type) VALUES
  ('Shopping', 'expense'),
  ('Dining Out', 'expense'),
  ('Food & Groceries', 'expense'),
  ('Transportation', 'expense'),
  ('Other Expense', 'expense'),
  ('Income', 'income'),
  ('Entertainment', 'expense'),
  ('Health & Pharmacy', 'expense'),
  ('Utilities', 'expense'),
  ('Travel', 'expense'),
  ('Education', 'expense'),
  ('Personal Care', 'expense'),
  ('Home & Garden', 'expense'),
  ('Electronics', 'expense'),
  ('Clothing', 'expense'),
  ('Sports & Fitness', 'expense'),
  ('Gifts & Donations', 'expense'),
  ('Taxes & Fees', 'expense'),
  ('Savings', 'income'),
  ('Miscellaneous', 'expense')
ON CONFLICT (name) DO NOTHING;

-- banks (stub for FK)
CREATE TABLE IF NOT EXISTS myfinance.banks (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name       text NOT NULL,
  code       text UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO myfinance.banks (id, name, code) VALUES
  ('11111111-1111-1111-1111-111111111111', 'Davivienda', 'DV'),
  ('22222222-2222-2222-2222-222222222222', 'Bancolombia', 'BC')
ON CONFLICT DO NOTHING;

-- accounts
CREATE TABLE IF NOT EXISTS myfinance.accounts (
  id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid NOT NULL REFERENCES auth.users(id),
  bank_id    uuid REFERENCES myfinance.banks(id),
  type       text NOT NULL DEFAULT 'credit_card',
  currency   text NOT NULL DEFAULT 'COP',
  last4      text,
  nickname   text,
  active     boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO myfinance.accounts (id, user_id, bank_id, type, last4, nickname) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   '00000000-0000-0000-0000-000000000001',
   '11111111-1111-1111-1111-111111111111',
   'credit_card', '8132', 'Visa Signature Davivienda'),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
   '00000000-0000-0000-0000-000000000001',
   '22222222-2222-2222-2222-222222222222',
   'credit_card', '4567', 'Black Bancolombia'),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc',
   '00000000-0000-0000-0000-000000000001',
   '22222222-2222-2222-2222-222222222222',
   'credit_card', '9012', 'Bancolombia Debito')
ON CONFLICT DO NOTHING;

-- transactions (400 rows — exceeds the >= 300 threshold)
CREATE TABLE IF NOT EXISTS myfinance.transactions (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       uuid NOT NULL REFERENCES auth.users(id),
  account_id    uuid REFERENCES myfinance.accounts(id),
  category_id   uuid REFERENCES myfinance.categories(id),
  type          text NOT NULL DEFAULT 'credit_card_purchase',
  amount        numeric(18,2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
  currency      text NOT NULL DEFAULT 'COP',
  description   text,
  occurred_at   timestamptz NOT NULL DEFAULT now(),
  source        text NOT NULL DEFAULT 'gmail',
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Insert 400 synthetic transactions using generate_series
INSERT INTO myfinance.transactions
  (user_id, account_id, category_id, type, amount, currency, description, occurred_at)
SELECT
  '00000000-0000-0000-0000-000000000001',
  'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
  (SELECT id FROM myfinance.categories ORDER BY name LIMIT 1 OFFSET (gs % 20)),
  'credit_card_purchase',
  (10000 + (gs * 317 % 500000))::numeric(18,2),
  'COP',
  'Smoke test transaction ' || gs,
  now() - (gs || ' hours')::interval
FROM generate_series(1, 400) AS gs;
