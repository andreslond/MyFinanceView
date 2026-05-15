# Task 0 — Supabase write gate (reusable template)

> Copy this task as **Task 0** in `openspec/changes/<id>/tasks.md` for any change that
> writes to the Supabase remote (Flyway migrate/baseline/repair/clean, ad-hoc DDL/DML via
> psql, MCP `apply_migration`, MCP `execute_sql`, or bulk data operations).
>
> Freshness expectations:
> - **24 h** — a green daily backup is recent enough for low-risk reads or additive migrations.
> - **60 min** — a green pre-op backup is expected before any destructive or high-risk write
>   (DROP, TRUNCATE, bulk UPDATE/DELETE, Flyway baseline on a non-empty DB).
>
> These are **operator discipline expectations**, not enforced gates. The system does not
> block writes programmatically; the operator should check before proceeding.

## Task 0 — Backup before Supabase write

### 0.1 Check last successful backup

Option A — via status endpoint (runner must be up):
```sh
curl -s http://myfinance-backup-runner:8080/status | jq '{lastSuccess, lastVerify}'
```
Confirm `lastSuccess.timestamp` is within the expected freshness window (see above).

Option B — read R2 directly:
```sh
rclone cat r2:my-finance-view-backups/status/last-success.json | jq '{timestamp, sha256}'
```

### 0.2 Trigger pre-op backup (if freshness requirement is 60 min)

From an allowlisted IP (see `traefik/dynamic/myfinance-preop.yml`):

```sh
export PREOP_SECRET=<from password manager>
echo '{"reason":"<this-change-id>"}' > /tmp/preop-reason.json
curl -X POST https://n8n.datachefnow.com/webhook/myfinance-backup-preop \
  -H "X-Webhook-Secret: $PREOP_SECRET" \
  -H "Content-Type: application/json" \
  --data-binary "@/tmp/preop-reason.json"
rm /tmp/preop-reason.json
```

Reason slug must match `^[A-Za-z0-9._+-]{3,60}$` (e.g. `flyway-baseline`, `v4.1-migration`).

Expected response: HTTP 200 with `{"artefact":"pre-op/...","sha256":"...","verifyResult":{"probes":[...]}}`.
All probes should show `passed: true`.

### 0.3 Note the artefact path

Record the `artefact` value from the response (e.g. `pre-op/2026-05-13T10-30-00Z-flyway-baseline.tar.age`)
in the change notes. If something goes wrong during the migration and a rollback is needed,
this path identifies exactly which snapshot to restore.

- [ ] 0.1 Confirm last successful backup is within freshness window
- [ ] 0.2 Trigger pre-op backup (required for destructive writes)
- [ ] 0.3 Note artefact path: `pre-op/____________________________`
