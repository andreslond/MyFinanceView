#!/bin/bash
# =====================================================================
# init-db.sh
#
# Postgres container entrypoint init script (mounted to
# /docker-entrypoint-initdb.d/00_init.sh inside the container). Runs once
# on first container start (when the data dir is empty).
#
# Two-phase init keeps local-only Supabase compatibility stubs OUT of the
# `backend/database/migrations/` folder that future Flyway adoption (TASK-DB-06)
# will manage:
#
#   1. backend/database/local/V000__local_supabase_stubs.sql  ← local + Testcontainers only
#   2. backend/database/migrations/V001..V003                  ← real schema, applied to Supabase too
#
# Future Flyway baseline points at `backend/database/migrations/` only. The local
# folder stays invisible to it.
# =====================================================================

set -euo pipefail

echo "[init-db] Phase 1/2 — applying local Supabase parity stubs..."
psql -v ON_ERROR_STOP=1 \
     -U "$POSTGRES_USER" \
     -d "$POSTGRES_DB" \
     -f /var/lib/myfinance/local/V000__local_supabase_stubs.sql

echo "[init-db] Phase 2/2 — applying schema migrations V001..Vn..."
for f in /var/lib/myfinance/migrations/V*__*.sql; do
    echo "[init-db]   -> $(basename "$f")"
    psql -v ON_ERROR_STOP=1 \
         -U "$POSTGRES_USER" \
         -d "$POSTGRES_DB" \
         -f "$f"
done

echo "[init-db] Done. myfinance schema is ready."
