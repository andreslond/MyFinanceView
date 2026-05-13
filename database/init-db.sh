#!/bin/bash
# =====================================================================
# init-db.sh
#
# Postgres container entrypoint init script (mounted to
# /docker-entrypoint-initdb.d/00_init.sh inside the container). Runs once
# on first container start (when the data dir is empty).
#
# Single-phase responsibility since `flyway-migrations`:
#
#   1. database/local/V000__local_supabase_stubs.sql  ← local + Testcontainers only
#
# Production migrations (V001..Vn) are NO LONGER applied by this script.
# Flyway runs at Spring Boot startup and applies them from the classpath
# (src/main/resources/db/migration/). See openspec/specs/database-migrations/.
# =====================================================================

set -euo pipefail

echo "[init-db] Applying local Supabase parity stubs (V000)..."
psql -v ON_ERROR_STOP=1 \
     -U "$POSTGRES_USER" \
     -d "$POSTGRES_DB" \
     -f /var/lib/myfinance/local/V000__local_supabase_stubs.sql

echo "[init-db] Done. Flyway will apply V001..Vn at Spring Boot startup."
