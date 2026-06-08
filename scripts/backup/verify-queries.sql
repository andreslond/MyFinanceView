-- verify-queries.sql v1 — restore probe suite for MyFinanceView backups.
--
-- SEMANTICS: these thresholds are SMOKE DETECTORS, not ALARMS. They catch
-- "the restore produced an empty or near-empty database" — a structural
-- failure. They do NOT catch row-level corruption that stays within the
-- threshold (e.g. losing 50 of 400 transactions, since 350 still >= 300).
-- A proper integrity alarm would compare against the previous successful
-- verify's counts (requires persistent state on R2; deferred).
--
-- WHEN TO RE-BASELINE: bump the floors after a meaningful schema change
-- (e.g. when `savings_goals` lands, add a probe with a sensible floor;
-- when `transactions` count durably exceeds 1000, raise its floor to ~700).
-- Always bump the version number at the top of this file when editing.
--
-- NOTE: the `latest_transaction_age_days` probe was deliberately DROPPED
-- (B3 fix). That signal conflates backup integrity with ingest health:
-- a vacation week or holiday dip would fire false alarms on a valid backup.
-- Recency monitoring is a separate concern and out of scope here.

-- probe: transactions_count  threshold: >= 300
SELECT count(*) FROM myfinance.transactions;

-- probe: accounts_count  threshold: >= 1
-- M8 fix: tests presence of backup data, not operator business state
-- (the previous ">= 3 active accounts" would fire false alarms if a card is archived).
SELECT count(*) FROM myfinance.accounts;

-- probe: categories_count  threshold: >= 19
SELECT count(*) FROM myfinance.categories;

-- probe: auth_users_count  threshold: >= 1
-- The verify-restore stub + pg_restore --data-only should produce >= 1 row.
SELECT count(*) FROM auth.users;
