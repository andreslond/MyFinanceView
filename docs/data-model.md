# Data Model — MyFinanceView

> Current state of the `myfinance` schema in Supabase, plus pending migrations. Authoritative when SPEC.md §4 says "ver docs/data-model.md".

---

## 1. Conventions

- Schema: `myfinance` (isolated from Supabase's `auth`, `storage`, `public`).
- Monetary: `numeric(18,2)`. Exchange rates: `numeric(18,8)`.
- Identifiers: `uuid`, default `uuid_generate_v4()` from extension `uuid-ossp` (or `extensions.uuid_generate_v4()` in Supabase).
- Timestamps: `timestamptz` in UTC.
- All user-owned tables have `user_id uuid REFERENCES auth.users(id) ON DELETE CASCADE`.
- All user-owned tables have RLS enabled with policies scoped by `auth.uid() = user_id`. Backend bypasses via service role but enforces in app layer.
- All user-owned tables have `updated_at timestamptz NOT NULL DEFAULT now()` plus a trigger calling `myfinance.trigger_set_updated_at()`.

## 2. Tables — Current Baseline (V001–V003 applied)

### `banks` — system table, shared
```sql
id          uuid PK
name        text NOT NULL
code        text UNIQUE          -- short code for email parser
created_at  timestamptz
```
RLS: read = all authenticated; write = service role only.

### `user_settings` — one row per user
```sql
id             uuid PK
user_id        uuid UNIQUE → auth.users
base_currency  text DEFAULT 'COP'
timezone       text DEFAULT 'America/Bogota'
locale         text DEFAULT 'es-CO'
created_at, updated_at
```

### `categories` — system + per-user
```sql
id          uuid PK
user_id     uuid → auth.users   -- NULL = system default
name        text NOT NULL       -- internal key, English
type        category_type       -- 'expense' | 'income'
icon, color text
is_active   boolean DEFAULT true
created_at, updated_at
```
19 system defaults at present. Pending TASK-DB-01: `display_name` column in Spanish (NOT NULL post-backfill).

### `accounts`
```sql
id          uuid PK
user_id     uuid → auth.users
bank_id     uuid → banks
type        account_type        -- 'checking' | 'savings' | 'credit_card'
currency    text
last4       text
nickname    text
active      boolean DEFAULT true
created_at, updated_at
```
3 active accounts. Pending TASK-DB-03: `cut_day int CHECK (1–28)`, `payment_day int CHECK (1–28)`.

### `transactions`
```sql
id                    uuid PK
user_id               uuid → auth.users
account_id            uuid → accounts
category_id           uuid → categories      -- current category (may be unconfirmed)
type                  transaction_type
amount                numeric(18,2) ≥ 0
currency              text
amount_base_currency  numeric(18,2) ≥ 0
description           text
notes                 text
occurred_at           timestamptz
source                transaction_source     -- 'gmail' | 'manual' | 'api'
external_id           text                   -- UNIQUE WHERE NOT NULL (idempotency)
raw_payload           jsonb                  -- original email for audit
created_at, updated_at
```
362 rows at 2026-05-11. Pending TASK-DB-02 (installments) and TASK-DB-05 (categorization fields) — see §3.

### `budgets`
```sql
id            uuid PK
user_id       uuid → auth.users
month         int CHECK (1–12)
year          int CHECK (2020–2100)
total_budget  numeric(18,2) ≥ 0
currency      text
created_at, updated_at
UNIQUE (user_id, month, year)
```
Empty (feature not yet active).

### `budget_categories`
```sql
id                uuid PK
user_id           uuid → auth.users    -- denormalized for RLS perf
budget_id         uuid → budgets
category_id       uuid → categories
allocated_amount  numeric(18,2) ≥ 0
created_at, updated_at
UNIQUE (budget_id, category_id)
```

### `exchange_rates` — system table
```sql
id              uuid PK
base_currency   text
target_currency text
rate            numeric(18,8) > 0
effective_date  date
created_at
UNIQUE (base_currency, target_currency, effective_date)
```
Empty. RLS: read = all authenticated; write = service role.

## 3. Backup & Migration Safety

> **Before running any migration against the Supabase remote, a verified backup is expected.**
>
> - **24 h** — a green daily backup is sufficient for additive migrations.
> - **60 min** — a pre-op snapshot should be triggered before destructive or high-risk writes
>   (DROP, TRUNCATE, Flyway baseline on a non-empty DB, bulk UPDATE/DELETE).
>
> These are operator discipline expectations, not enforced programmatic gates.
> Use the checklist template at [`openspec/templates/supabase-write-checklist.md`](../openspec/templates/supabase-write-checklist.md)
> as task 0 in any change that writes to Supabase.
>
> For the full backup procedure (restore from snapshot, key rotation, disaster drill),
> see [`docs/development-guide.md §11–12`](development-guide.md) and
> [`scripts/backup/README.md`](../scripts/backup/README.md).

## 4. Pending Migrations (TASK-DB-01..05)

> Order matters. FK dependencies force this sequence.

### V004 — TASK-DB-01: categories.display_name (ES)
```sql
ALTER TABLE myfinance.categories ADD COLUMN display_name text;
-- backfill the 19 system categories (Restaurantes y Cafés, Mercado y Supermercado, …)
ALTER TABLE myfinance.categories ALTER COLUMN display_name SET NOT NULL;
```

### V005 — TASK-DB-03: accounts cut/payment day
```sql
ALTER TABLE myfinance.accounts
  ADD COLUMN cut_day      int CHECK (cut_day BETWEEN 1 AND 28),
  ADD COLUMN payment_day  int CHECK (payment_day BETWEEN 1 AND 28);
-- Davivienda Signature: cut_day = 15
-- Black Bancolombia, Bancolombia débito: por confirmar
```
Plus function `myfinance.get_billing_period(p_account_id uuid, p_reference_date date) RETURNS (start date, end date)`.

### V006 — TASK-DB-02: installments
```sql
ALTER TABLE myfinance.transactions
  ADD COLUMN installments_total     int NOT NULL DEFAULT 1,
  ADD COLUMN installment_number     int NOT NULL DEFAULT 1,
  ADD COLUMN parent_transaction_id  uuid REFERENCES myfinance.transactions(id) ON DELETE SET NULL;
ALTER TABLE myfinance.transactions
  ADD CONSTRAINT chk_installment_le_total CHECK (installment_number <= installments_total),
  ADD CONSTRAINT chk_installments_positive CHECK (installments_total >= 1);
```

### V007 — TASK-DB-04: merchants table
```sql
CREATE TABLE myfinance.merchants (
  id                 uuid PK DEFAULT extensions.uuid_generate_v4(),
  user_id            uuid → auth.users,
  raw_pattern        text NOT NULL,
  normalized_name    text NOT NULL,
  category_id        uuid → categories,
  confidence         numeric DEFAULT 0.5 CHECK (BETWEEN 0 AND 1),
  match_count        int DEFAULT 0,
  last_confirmed_at  timestamptz,
  created_at, updated_at,
  UNIQUE (user_id, raw_pattern)
);
CREATE INDEX ON myfinance.merchants USING gin(to_tsvector('simple', raw_pattern));
-- RLS enabled, owner-only.
-- Seed 15+ known merchants (DIDI, RAPPI, APPLE.COM, JUAN VALDEZ, …)
```

### V008 — TASK-DB-05: transactions categorization fields
```sql
ALTER TABLE myfinance.transactions
  ADD COLUMN merchant_id                uuid REFERENCES myfinance.merchants(id),
  ADD COLUMN ai_suggested_category_id   uuid REFERENCES myfinance.categories(id),
  ADD COLUMN categorization_confidence  numeric CHECK (BETWEEN 0 AND 1),
  ADD COLUMN category_confirmed         boolean NOT NULL DEFAULT false;
CREATE INDEX idx_unconfirmed_txs
  ON myfinance.transactions(account_id, occurred_at DESC)
  WHERE category_confirmed = false;
```

### V009 — TASK-SG-DB-01: savings_goals + contributions
See [`plans/savings-goals-plan.md §3`](../plans/savings-goals-plan.md). Tables `savings_goals` and `savings_goal_contributions` with RLS, plus Storage bucket `goal-avatars`.

## 5. ENUMs (already created in V001)

```sql
account_type        : 'checking' | 'savings' | 'credit_card'
transaction_type    : 'credit_card_purchase' | 'debit_purchase'
                    | 'credit_card_payment' | 'incoming_transfer'
                    | 'outgoing_transfer'  | 'incoming_payment'
transaction_source  : 'gmail' | 'manual' | 'api'
category_type       : 'expense' | 'income'
```

## 6. Index Strategy (current + pending)

| Table | Index | Type | Purpose |
|---|---|---|---|
| `transactions` | `external_id` | UNIQUE PARTIAL (`WHERE NOT NULL`) | Idempotency |
| `transactions` | `user_id, occurred_at DESC` | BTREE | Dashboard time-range |
| `transactions` | `user_id, type` | BTREE | Type-filtered aggregation |
| `transactions` | `account_id` | BTREE | Account-scoped queries |
| `transactions` | `category_id` | BTREE PARTIAL | Category reports |
| `transactions` | `(account_id, occurred_at DESC) WHERE category_confirmed = false` | BTREE PARTIAL | Pending-review queue (V008) |
| `accounts` | `user_id` | BTREE | User listing |
| `categories` | `user_id WHERE user_id IS NOT NULL` | BTREE PARTIAL | User categories |
| `budgets` | `user_id, year, month` | UNIQUE | One budget per month |
| `merchants` | `raw_pattern` (gin tsvector) | GIN | Fuzzy lookup (V007) |
| `savings_goals` | `user_id, status, priority` | BTREE | Goal listing (V009) |
| `savings_goal_contributions` | `goal_id, occurred_at DESC` | BTREE | History (V009) |

## 7. Open Decisions

| Topic | Status | Reference |
|---|---|---|
| Cut/payment day Black Bancolombia | unknown | TASK-DB-03 |
| `ar_costing` schema ownership | unknown | TASK-DT-04 |
| Drop `public` schema (Chatwoot) | needs explicit confirm | TASK-DT-02 |
| Savings goals: contributions ↔ transactions linkage | tentative (loose via `transaction_id`) | [savings-goals-plan §8](../plans/savings-goals-plan.md#8-decisiones-abiertas-para-mañana-en-notion) |
