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
19 system defaults at present. Pending TASK-DB-01 (V004 — `display_name` column in Spanish, NOT NULL post-backfill) — implementación cubierta por el change `backend-mvp-readonly`.

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
3 active accounts. Pending TASK-DB-03 (V006 post-`backend-mvp-readonly`): `cut_day int CHECK (1–28)`, `payment_day int CHECK (1–28)`.

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
362 rows at 2026-05-11. Pending TASK-DB-02 (V007 — installments) y TASK-DB-05 (V008 — categorization fields) — see §3. El change `backend-mvp-readonly` agrega `merchant_id UUID` (nullable, FK a `myfinance.merchants` via V005); las 362 filas históricas quedan con `merchant_id = NULL` hasta que el usuario re-categorize via PATCH.

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

## 3. Pending Migrations (TASK-DB-01..05)

> Order matters. FK dependencies force this sequence. **Cola revisada 2026-06-02** — el change `backend-mvp-readonly` consume V004 y V005 (split por operator decision sobre el adv-review B1+B2); el resto de la cola corre +1 vs el plan original.
>
> Order matters. FK dependencies force this sequence.
>
> **Pre-flight expectation (operator discipline, documentation-only gate):** before applying any migration in this section to the Supabase remote, the operator **should** verify either a daily snapshot < 24 h old or run `MyFinanceBackup-PreOp` from the n8n UI within the last 60 minutes. Full procedure and rationale at [`docs/development-guide.md §12 Backup & Disaster Recovery`](development-guide.md#12-backup--disaster-recovery); spec source archived at `archive/openspec-legacy/specs/database-backups/spec.md` (historical). No CI / runtime enforcement — relies on operator + the `adversarial-review` skill flagging missing snapshot evidence on change proposals.

### V004 — TASK-DB-01: categories.display_name (ES) — owned by `backend-mvp-readonly`
```sql
ALTER TABLE myfinance.categories ADD COLUMN display_name text;
-- backfill the 19 system categories (Restaurantes y Cafés, Mercado y Supermercado, …)
UPDATE myfinance.categories SET display_name = name WHERE display_name IS NULL; -- fallback defensivo
ALTER TABLE myfinance.categories ALTER COLUMN display_name SET NOT NULL;
```

### V005 — TASK-DB-04: merchants table + transactions.merchant_id FK — owned by `backend-mvp-readonly`
```sql
CREATE TABLE myfinance.merchants (
  id                 uuid PK DEFAULT extensions.uuid_generate_v4(),
  user_id            uuid → auth.users,
  display_name       text NOT NULL,
  raw_pattern        text NOT NULL,
  category_id        uuid NOT NULL → categories,
  confidence         numeric(3,2) NOT NULL DEFAULT 0.50 CHECK (BETWEEN 0.00 AND 1.00),
  match_count        int NOT NULL DEFAULT 0 CHECK (>= 0),
  last_confirmed_at  timestamptz,
  created_at, updated_at,
  UNIQUE (user_id, raw_pattern)
);
-- RLS enabled, owner-only policies análogas a accounts_* de V002.
-- Sin seed: el feedback loop del backend siembra merchants on-demand.

ALTER TABLE myfinance.transactions
  ADD COLUMN merchant_id uuid REFERENCES myfinance.merchants(id) ON DELETE SET NULL;
CREATE INDEX idx_transactions_merchant_id
  ON myfinance.transactions(merchant_id)
  WHERE merchant_id IS NOT NULL;
```

### V006 — TASK-DB-03: accounts cut/payment day
```sql
ALTER TABLE myfinance.accounts
  ADD COLUMN cut_day      int CHECK (cut_day BETWEEN 1 AND 28),
  ADD COLUMN payment_day  int CHECK (payment_day BETWEEN 1 AND 28);
-- Davivienda Signature: cut_day = 15
-- Black Bancolombia, Bancolombia débito: por confirmar
```
Plus function `myfinance.get_billing_period(p_account_id uuid, p_reference_date date) RETURNS (start date, end date)`.

### V007 — TASK-DB-02: installments
```sql
ALTER TABLE myfinance.transactions
  ADD COLUMN installments_total     int NOT NULL DEFAULT 1,
  ADD COLUMN installment_number     int NOT NULL DEFAULT 1,
  ADD COLUMN parent_transaction_id  uuid REFERENCES myfinance.transactions(id) ON DELETE SET NULL;
ALTER TABLE myfinance.transactions
  ADD CONSTRAINT chk_installment_le_total CHECK (installment_number <= installments_total),
  ADD CONSTRAINT chk_installments_positive CHECK (installments_total >= 1);
```

### V008 — TASK-DB-05: transactions categorization fields (sin `merchant_id`, ya en V005)
```sql
ALTER TABLE myfinance.transactions
  ADD COLUMN ai_suggested_category_id   uuid REFERENCES myfinance.categories(id),
  ADD COLUMN categorization_confidence  numeric CHECK (BETWEEN 0 AND 1),
  ADD COLUMN category_confirmed         boolean NOT NULL DEFAULT false;
CREATE INDEX idx_unconfirmed_txs
  ON myfinance.transactions(account_id, occurred_at DESC)
  WHERE category_confirmed = false;
```

### V009 — TASK-SG-DB-01: savings_goals + contributions
See [`plans/savings-goals-plan.md §3`](../plans/savings-goals-plan.md). Tables `savings_goals` and `savings_goal_contributions` with RLS, plus Storage bucket `goal-avatars`.

## 4. ENUMs (already created in V001)

```sql
account_type        : 'checking' | 'savings' | 'credit_card'
transaction_type    : 'credit_card_purchase' | 'debit_purchase'
                    | 'credit_card_payment' | 'incoming_transfer'
                    | 'outgoing_transfer'  | 'incoming_payment'
transaction_source  : 'gmail' | 'manual' | 'api'
category_type       : 'expense' | 'income'
```

## 5. Index Strategy (current + pending)

| Table | Index | Type | Purpose |
|---|---|---|---|
| `transactions` | `external_id` | UNIQUE PARTIAL (`WHERE NOT NULL`) | Idempotency |
| `transactions` | `user_id, occurred_at DESC` | BTREE | Dashboard time-range |
| `transactions` | `user_id, type` | BTREE | Type-filtered aggregation |
| `transactions` | `account_id` | BTREE | Account-scoped queries |
| `transactions` | `category_id` | BTREE PARTIAL | Category reports |
| `transactions` | `merchant_id WHERE merchant_id IS NOT NULL` | BTREE PARTIAL | Merchant-scoped queries (V005) |
| `transactions` | `(account_id, occurred_at DESC) WHERE category_confirmed = false` | BTREE PARTIAL | Pending-review queue (V008) |
| `accounts` | `user_id` | BTREE | User listing |
| `categories` | `user_id WHERE user_id IS NOT NULL` | BTREE PARTIAL | User categories |
| `budgets` | `user_id, year, month` | UNIQUE | One budget per month |
| `merchants` | `(user_id, raw_pattern)` | UNIQUE | Anti-duplicate por usuario (V005) |
| `savings_goals` | `user_id, status, priority` | BTREE | Goal listing (V009) |
| `savings_goal_contributions` | `goal_id, occurred_at DESC` | BTREE | History (V009) |

## 6. Open Decisions

| Topic | Status | Reference |
|---|---|---|
| Cut/payment day Black Bancolombia | unknown | TASK-DB-03 |
| `ar_costing` schema ownership | unknown | TASK-DT-04 |
| Drop `public` schema (Chatwoot) | needs explicit confirm | TASK-DT-02 |
| Savings goals: contributions ↔ transactions linkage | tentative (loose via `transaction_id`) | [savings-goals-plan §8](../plans/savings-goals-plan.md#8-decisiones-abiertas-para-mañana-en-notion) |
