-- =====================================================================
-- V000__local_supabase_stubs.sql
--
-- LOCAL-ONLY MIGRATION. DO NOT APPLY TO SUPABASE REMOTE.
--
-- Supabase ships with the `auth` schema, `auth.users` table, and `auth.uid()`
-- function pre-provisioned. A vanilla `postgres:17` image (used by local Docker
-- Compose and Testcontainers) does NOT. V001 references `auth.users(id)` as FK
-- target and V002 uses `auth.uid()` in RLS policies — both fail to apply
-- against vanilla Postgres without these stubs.
--
-- This file creates the minimum surface needed for V001..V003 to apply locally.
-- It is intentionally idempotent (IF NOT EXISTS / OR REPLACE) so a clean re-run
-- of `docker compose down -v && up -d` is safe.
--
-- Naming: V000 (pre-V001) so `docker-entrypoint-initdb.d` picks it up first
-- alphabetically. When Flyway is eventually wired (post-MVP), this file MUST
-- be excluded from the production baseline — see [docs/data-model.md].
-- =====================================================================

-- Supabase built-in roles. V002 RLS policies grant to `authenticated`, `anon`,
-- and `service_role`. CREATE ROLE has no IF NOT EXISTS, so use DO blocks.
DO $$ BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anon') THEN
        CREATE ROLE anon NOLOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'authenticated') THEN
        CREATE ROLE authenticated NOLOGIN NOINHERIT;
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'service_role') THEN
        CREATE ROLE service_role NOLOGIN NOINHERIT BYPASSRLS;
    END IF;
END $$;

CREATE SCHEMA IF NOT EXISTS auth;

-- Minimal auth.users stub. Only the `id` column is referenced by V001 FKs.
-- Real Supabase auth.users has email, encrypted_password, raw_user_meta_data, etc.;
-- locally we don't need them.
CREATE TABLE IF NOT EXISTS auth.users (
    id uuid PRIMARY KEY
);

-- Stub for the auth.uid() function used by V002 RLS policies.
-- Reads the JWT subject from the PostgREST/PostgreSQL session setting
-- `request.jwt.claim.sub` if present; returns NULL otherwise.
-- Tests that need a specific user can `SELECT set_config('request.jwt.claim.sub', '<uuid>', true);`.
CREATE OR REPLACE FUNCTION auth.uid()
    RETURNS uuid
    LANGUAGE sql
    STABLE
AS $$
    SELECT NULLIF(current_setting('request.jwt.claim.sub', true), '')::uuid;
$$;

-- Seed a single local development user so FK constraints in V001 can be
-- satisfied by test fixtures and the seed in V003. UUID is deterministic so
-- tests can reference it without env vars.
INSERT INTO auth.users (id) VALUES ('00000000-0000-0000-0000-000000000001'::uuid)
ON CONFLICT (id) DO NOTHING;
