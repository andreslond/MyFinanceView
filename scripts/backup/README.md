# scripts/backup — MyFinanceView Backup Pipeline

## 2.5.1 Overview

This directory implements the automated backup and restore-verification pipeline for the
MyFinanceView Supabase database (schema `myfinance` + `auth.users`).

Key facts (v1, operator decision 2026-06-01):
- Runs as a sidecar container (`myfinance-backup-runner`) on the same VPS as n8n.
- Produces `pg_dump --format=custom` snapshots encrypted with `age` (single recipient).
- Stores encrypted snapshots on Cloudflare R2 (`my-finance-view-backups` bucket).
- Chains a full restore-verify after every daily backup.
- Alerts via **ntfy.sh push + Resend transactional email** (`alerts@datachefnow.com` on the verified `datachefnow.com` domain) in parallel on any failure.
- **No in-cluster dead-man-switch in v1** (Uptime Kuma deferred); host-down detection relies on the operator's external uptime monitor on `n8n.datachefnow.com`.

For the authoritative capability specification see:
[`openspec/specs/database-backups/spec.md`](../../openspec/specs/database-backups/spec.md)
(populated after `/opsx:archive`).

**Scope notes:**
- **v3 (May 2026):** dual-recipient encryption, healthchecks.io off-VPS pinger, and the public pre-op webhook with Traefik IP allowlist were all cut under Gate C triage. Local-forensic adversary out of threat model.
- **v1 (June 2026):** R2 lifecycle rules reduced from 5 to 1 (only `daily/` 30d); alerting cut from 3 channels (ntfy + Gmail SMTP + Kuma) to 2 (ntfy + Resend HTTP API); Uptime Kuma in-VPS dead-man-switch and the `MyFinanceBackup-Watchdog` workflow dropped. See `openspec/changes/supabase-backup-policy/proposal.md` "Threat model" section and `design.md` Decisions 3 + 7.

---

## 2.5.2 Architecture

See [`openspec/changes/supabase-backup-policy/design.md §10`](../../openspec/changes/supabase-backup-policy/design.md)
for the sidecar architecture diagram.

Summary:

```
n8n (Schedule 02:30 BOG) ──POST /run/daily──> myfinance-backup-runner:8080
                                                  │
                                          workers/backup-daily.sh
                                                  │
                                    pg_dump ──> age encrypt ──> rclone → R2
                                                  │
                                          workers/verify-restore.sh (chained)
                                                  │
                                    ephemeral postgres:17 container
                                                  │
                                          verify-queries.sql probes
                                                  │
                                    status/last-success.json + last-verify.json → R2
```

**v1 (operator decision 2026-06-01):** no Kuma success push at the bottom of the success path — Kuma was dropped together with the `MyFinanceBackup-Watchdog` workflow. On failure, `dispatch_alert` fires ntfy + Resend in parallel; success is silent.

**Single-recipient age encryption:**
- `recipients/primary.txt` — baked into runner image; primary public key.
- The matching private identity lives on the operator's Windows PC at
  `%USERPROFILE%\.config\myfinance-backup\age-identity-primary.txt` AND on one paper
  copy at physical location A (home safe / firebox / locked drawer — operator's choice).

**Alpine pin:** base image is `node:22-alpine3.21`. Before activation and on each key-rotation
cycle, verify the pin is still current:
```sh
docker pull node:22-alpine3.21
docker run --rm node:22-alpine3.21 sh -c "apk update && apk search postgresql17-client | head -1"
# Must return a postgresql17-client-... package line.
```
If the package has been renamed or the image retired, update `Dockerfile.runner` to the next
Alpine LTS (e.g. `node:22-alpine3.22`) and re-run the verify before re-building. Document the
verified pin and date here for the next operator.

---

## 2.5.3 Environment setup

The backup runner reads `scripts/backup/.env.local` (gitignored) when started by Docker Compose.

**The variable contract lives in the root `.env.example` under the "Backup pipeline" section.**
There is no duplicate `.env.example` here — DRY.

To set up on the VPS:
```sh
# On the VPS, inside the repo root:
cp .env.example scripts/backup/.env.local
# Edit scripts/backup/.env.local and fill in every BACKUP_* and MYFINANCE_BACKUP_* value.
chmod 600 scripts/backup/.env.local
```

rclone configuration (`~/.config/rclone/rclone.conf` or system-wide `/etc/rclone.conf`):
```ini
[r2]
type = s3
provider = Cloudflare
access_key_id = <BACKUP_R2_ACCESS_KEY_ID>
secret_access_key = <BACKUP_R2_SECRET_ACCESS_KEY>
endpoint = https://<BACKUP_R2_ACCOUNT_ID>.r2.cloudflarestorage.com
acl = private
```

---

## 2.5.4 Operator runbook

### Install on VPS

```sh
# From the repo root on the VPS:
docker compose \
  -f n8n/docker-compose.yml \
  -f scripts/backup/docker-compose.yml \
  up -d myfinance-backup-runner

# Verify it started:
docker ps | grep myfinance-backup-runner
# From inside the n8n container:
curl http://myfinance-backup-runner:8080/healthz
# Expected: {"status":"ok","version":"1.0.0"}
```

### Smoke test

```sh
bash scripts/backup/test-smoke.sh
# Boots local Postgres, seeds schema, runs backup-daily.sh, chains verify.
# Exits 0 on success.
```

### Manual pre-op backup (v3 — n8n UI, no webhook)

PreOp is triggered manually from the n8n UI (the public webhook + Traefik IP allowlist
were cut under v3). Steps:

1. Open the n8n UI on the VPS, navigate to the `MyFinanceBackup-PreOp` workflow.
2. Open the **Set Reason** node and edit the `reason` field — must match
   `^[A-Za-z0-9._+-]{3,60}$` (e.g. `flyway-baseline`, `v4.1-migration`,
   `manual-cleanup-2026-06-01`). Spaces and special chars other than `. _ + -` are rejected.
3. Click **Execute Workflow** at the top.
4. Watch the execution log. The `Validate Reason` node fails fast with a clear error
   if the regex doesn't match. The `POST /run/preop` node returns the final JSON
   `{"artefact":"pre-op/...","sha256":"...","verifyResult":{...}}` on success.

The runner (`backup-preop.sh`) also validates the regex as defense in depth.

### Manual restore from R2 (primary identity)

```sh
# On the operator's Windows PC:
# 1. Download the snapshot from R2 (Cloudflare dashboard or rclone).
rclone copy r2:my-finance-view-backups/daily/2026-05-13.tar.age .

# 2. Decrypt using the primary identity (in %USERPROFILE%\.config\myfinance-backup\).
age -d -i %USERPROFILE%\.config\myfinance-backup\age-identity-primary.txt ^
    -o snapshot.tar 2026-05-13.tar.age

# 3. Extract:
tar -xf snapshot.tar
# Produces: auth-users.dump, myfinance.dump, README.txt

# 4. Restore to a local Postgres (adjust connection string as needed):
pg_restore -h localhost -p 5432 -U postgres -d myfinance_restore \
    --data-only --table=users -Fc auth-users.dump
pg_restore -h localhost -p 5432 -U postgres -d myfinance_restore \
    -Fc myfinance.dump
```

### Manual restore from paper primary identity (PC lost)

If the Windows PC is lost or unavailable, retrieve envelope #1 from physical location A:

```sh
# Type the primary identity from paper into a file (or scan and OCR carefully):
nano primary-identity.txt
# Paste the AGE-SECRET-KEY-... line exactly as printed.

# Decrypt:
age -d -i primary-identity.txt -o snapshot.tar <snapshot>.tar.age

# Verify contents:
tar -tf snapshot.tar
# Expected: auth-users.dump, myfinance.dump, README.txt

# Wipe after recovery:
shred -u primary-identity.txt
rm snapshot.tar
```

---

## 2.5.5 Key rotation

**Recommended cadence:** rotate all secrets annually (or immediately after a suspected compromise).

| Secret | Rotation procedure |
|---|---|
| **age primary identity** | Generate new key pair with `age-keygen`. Replace `recipients/primary.txt`. Rebuild + redeploy the runner image (key is baked in). Re-print paper and re-file at location A. Update n8n credential `MYFINANCE_BACKUP_AGE_IDENTITY` to the new primary identity. Old snapshots still decrypt with the old identity (keep the old paper until snapshots expire from R2 lifecycle policy). |
| **R2 token** | Create new token in Cloudflare R2 dashboard. Update `BACKUP_R2_ACCESS_KEY_ID` and `BACKUP_R2_SECRET_ACCESS_KEY` in `scripts/backup/.env.local` and rclone config. Revoke old token. Restart runner. |
| **Resend API key** | Revoke in the Resend dashboard (https://resend.com/api-keys). Generate a new key scoped to `Sending access` only on the verified `datachefnow.com` domain. Update `MYFINANCE_BACKUP_RESEND_API_KEY` in `.env.local` and n8n credential. Restart runner. Sender (`MYFINANCE_BACKUP_ALERT_FROM = alerts@datachefnow.com`) and recipient (`MYFINANCE_BACKUP_ALERT_TO`) do not rotate. |
| **ntfy topic** | Generate a new unguessable 32+ char slug. Update `MYFINANCE_BACKUP_NTFY_TOPIC` in `.env.local` and n8n credential. Re-subscribe from operator phone. Restart runner. |
| **Runner shared secret** | Generate a new 32-char secret. Update `MYFINANCE_BACKUP_RUNNER_SECRET` in `.env.local` AND in n8n credential. Restart runner. |

**v1 cuts (operator decision 2026-06-01) — secrets no longer rotated:**
- `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` — replaced by Resend.
- `MYFINANCE_BACKUP_KUMA_PUSH_URL` — Uptime Kuma in-cluster dead-man-switch deferred.
- `MYFINANCE_BACKUP_HEALTHCHECKS_URL` — off-VPS dead-man-switch deferred (was already deferred in v3).

Reinstate any of these by re-adding the env var to `.env.example`, the corresponding worker/workflow wiring, AND a row to the table above.

---

## 2.5.6 Disaster scenarios

| Scenario | Recoverable? | Path |
|---|---|---|
| **Paper AND PC primary identity simultaneously lost** | **UNRECOVERABLE** | Both copies of the only key gone. Every existing encrypted snapshot is permanently unreadable. Mitigation: maintain good paper hygiene (see §2.5.7 annual drill); store paper at a location protected from the same events that would destroy the PC (fire, flood, theft). |
| Paper destroyed (PC intact) | YES | Decrypt with PC identity. Re-print paper immediately, re-file at location A. |
| PC compromised (primary identity exposed) but paper safe | YES | Generate a new key pair on a clean machine. Replace `recipients/primary.txt`, rebuild and redeploy runner. From this point new snapshots use the new key. Old snapshots remain decryptable with the compromised key (rotate by re-encrypting recent monthly archives or accept that the compromised key remains valid for the lifetime of old snapshots). Re-print and re-file new paper. |
| VPS gone + R2 gone simultaneously | YES (≤ 1 month data loss) | Restore from monthly external-disk archive. Operator pulls monthly archive to external disk (calendar reminder — see §10 in tasks.md). |
| R2 only gone (VPS intact) | YES | If the runner's persistent bind-mount still has a recent status/logs directory, the last snapshot may still be in the working dir. Otherwise restore from external-disk monthly archive. |
| VPS only gone (R2 intact) | YES | Re-provision VPS, re-deploy runner, restore from R2 using primary identity. |

**Integration test:** `test-smoke.sh` converts the bash workers from "untested" to "tested against
real Postgres" (project rule `base-standards.md §5` — no mocking the database). Until CI is
configured, run `bash scripts/backup/test-smoke.sh` manually before merging any change to
`scripts/backup/*.sh`.

---

## 2.5.7 Annual disaster drill checklist (January habit)

Perform every January (calendar reminder set in tasks.md §10.6):

- [ ] Retrieve envelope #1 (primary identity) from location A. Confirm the paper is legible and undamaged.
- [ ] On a clean machine, download one snapshot from R2 (`rclone copy r2:my-finance-view-backups/daily/<latest>.tar.age .`).
- [ ] Decrypt with PRIMARY identity: `age -d -i primary-identity-typed.txt -o /tmp/check-primary.tar <snapshot>.tar.age`. Confirm `tar -tf /tmp/check-primary.tar` lists `auth-users.dump`, `myfinance.dump`, `README.txt`.
- [ ] Wipe `/tmp/check-primary.tar` and the typed identity file (`shred -u`).
- [ ] If the paper is smudged, water-damaged, or illegible: re-print and re-file BEFORE returning the envelope.
- [ ] Upload drill result to R2:
  ```sh
  cat > /tmp/last-drill.json <<EOF
  {"timestamp":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","paperIntact":true,"reprinted":false,"snapshotUsed":"daily/<filename>","operatorInitial":"AT"}
  EOF
  rclone copy /tmp/last-drill.json r2:my-finance-view-backups/status/
  rm /tmp/last-drill.json
  ```
  This upload records the drill on R2 for future audit. **v1 (operator decision 2026-06-01):** no automated `drill OVERDUE` alert (the Watchdog workflow at `tasks.md` §7.4 was dropped together with Uptime Kuma); the annual calendar reminder set up at `tasks.md` §10.6 is the only cadence cue.
- [ ] Return envelope to location A.
