# Supabase write checklist — task 0 template

> Copy this into the **first task** (task 0) of any OpenSpec change whose
> implementation touches Supabase remote (Flyway migrate/baseline/repair/clean,
> ad-hoc DDL/DML via psql, MCP `apply_migration`, MCP `execute_sql`, or any
> other write against `db.<project>.supabase.co` / the Session Pooler endpoint).
>
> This checklist is **operator discipline** — nothing in code or CI enforces it.
> The honest framing: if it is not in your change's tasks.md, you will forget
> to do it. See `openspec/changes/supabase-backup-policy/proposal.md` "Process
> gate is documentation-only" requirement.

---

## Task 0 — Verify recent backup before any Supabase write

- [ ] 0.1 Read the latest backup status from R2:
  ```sh
  rclone cat r2:my-finance-view-backups/status/last-success.json
  rclone cat r2:my-finance-view-backups/status/last-verify.json
  ```
  Confirm both files exist and `allPassed: true` is set in `last-verify.json`.

- [ ] 0.2 Check freshness — pick ONE of the two acceptable paths:

  **Path A — recent daily snapshot (≤ 24 h old):**
  - Parse `last-success.json.timestamp` and confirm it is within the last 24 h.
  - Confirm `last-verify.json.timestamp` is within the last 24 h AND
    `allPassed: true`.
  - If both true → you may proceed with the write.

  **Path B — pre-op snapshot (≤ 60 min old):**
  - In the n8n UI on the VPS, open the `MyFinanceBackup-PreOp` workflow.
  - Edit the **Set Reason** node's `reason` field to describe this change
    (regex `^[A-Za-z0-9._+-]{3,60}$`, e.g. `flyway-v005-add-savings-goals`).
  - Click **Execute Workflow**. Wait for the `POST /run/preop` node to return
    HTTP 200 with `{"artefact":"pre-op/...","verifyResult":{"allPassed":true,...}}`.
  - Confirm `last-preop.json` on R2 has been updated within the last 60 min.
  - If yes → you may proceed with the write.

- [ ] 0.3 Record the chosen path in this change's `progress.md` under
  `decisions_pending_design_update` so the adversarial-review step can audit
  that a fresh backup existed before the write:

  ```yaml
  decisions_pending_design_update:
    - "Pre-write backup verified: <path A | path B>, artefact: <path>, sha256: <hash>, timestamp: <iso8601>"
  ```

- [ ] 0.4 ONLY after 0.1-0.3 pass, begin the actual write tasks of the change.

---

## When to skip this checklist

This checklist is REQUIRED whenever the change writes to Supabase remote.
It is NOT required when:
- The change touches only local development (Docker Compose Postgres on `LOCAL_DB_PORT`).
- The change is documentation, agent config, OpenSpec metadata, or CI.
- The change touches only the backup pipeline itself (`scripts/backup/`,
  `openspec/changes/supabase-backup-policy/`) — by definition the backup is
  the thing being modified, and `test-smoke.sh` covers integration.

If you are unsure, default to including the checklist. The cost is ~10 minutes
of operator time; the cost of a fat-fingered destructive write without a
recent backup is the entire dataset.
