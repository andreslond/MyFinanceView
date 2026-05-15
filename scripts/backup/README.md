# scripts/backup — MyFinanceView Backup Pipeline

## 2.5.1 Overview

This directory implements the automated backup and restore-verification pipeline for the
MyFinanceView Supabase database (schema `myfinance` + `auth.users`).

Key facts:
- Runs as a sidecar container (`myfinance-backup-runner`) on the same VPS as n8n.
- Produces `pg_dump --format=custom` snapshots encrypted with `age` (dual-recipient).
- Stores encrypted snapshots on Cloudflare R2 (`my-finance-view-backups` bucket).
- Chains a full restore-verify after every daily backup.
- Alerts via ntfy.sh push + Gmail SMTP on any failure.
- Monitors liveness via Uptime Kuma (in-VPS) + healthchecks.io (off-VPS).

For the authoritative capability specification see:
[`openspec/specs/database-backups/spec.md`](../../openspec/specs/database-backups/spec.md)
(populated after `/opsx:archive`).

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
                                                  │
                                    Kuma ping + healthchecks.io ping
```

**Two-recipient age encryption:**
- `recipients/primary.txt` — baked into runner image; primary public key.
- `recipients/recovery.txt` — baked into runner image; recovery public key.
- Either corresponding private identity alone can decrypt any snapshot.

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

### Manual pre-op backup via webhook

From an IP-allowlisted machine (see `traefik/dynamic/myfinance-preop.yml`):

```sh
# Store the secret in an env var — never type it on the command line.
export PREOP_SECRET=<from password manager>

echo '{"reason":"flyway-baseline"}' > /tmp/preop-reason.json
curl -X POST https://n8n.datachefnow.com/webhook/myfinance-backup-preop \
  -H "X-Webhook-Secret: $PREOP_SECRET" \
  -H "Content-Type: application/json" \
  --data-binary "@/tmp/preop-reason.json"
rm /tmp/preop-reason.json
# Expected: HTTP 200 with {"artefact":"pre-op/...","sha256":"...","verifyResult":{...}}
```

Reason slug must match `^[A-Za-z0-9._+-]{3,60}$` (e.g. `flyway-baseline`, `v4.1-migration`).
Spaces, exclamation marks, and other special characters are rejected with HTTP 400.

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

### Manual restore from R2 (recovery identity — disaster scenario)

On a CLEAN machine (not the operator's primary PC), retrieve envelope #2 from location B:

```sh
# Type the recovery identity from paper into a file (or scan and type carefully):
nano recovery-identity.txt
# Paste the AGE-SECRET-KEY-... line exactly as printed.

# Decrypt:
age -d -i recovery-identity.txt -o snapshot.tar <snapshot>.tar.age

# Verify contents:
tar -tf snapshot.tar
# Expected: auth-users.dump, myfinance.dump, README.txt

# Wipe after recovery:
shred -u recovery-identity.txt
rm snapshot.tar
```

---

## 2.5.5 Key rotation

**Recommended cadence:** rotate all secrets annually (or immediately after a suspected compromise).

| Secret | Rotation procedure |
|---|---|
| **age recipients (primary + recovery)** | Generate new key pair(s) with `age-keygen`. Replace `recipients/primary.txt` and/or `recipients/recovery.txt`. Rebuild + redeploy the runner image (keys are baked in). Re-encrypt any snapshots you want recoverable with new keys (or accept that old snapshots require old identity). Re-print papers and re-file. Update n8n credential `MYFINANCE_BACKUP_AGE_IDENTITY` to the new primary identity. |
| **age primary identity** | Generate new key pair. Replace `recipients/primary.txt` in repo. Rebuild + redeploy image. Re-print and re-file at location A. Update n8n credential. Old snapshots still decrypt with old identity (keep the old paper until re-encrypted or expired). |
| **age recovery identity** | Generate new key pair in an isolated temp directory. Replace `recipients/recovery.txt` in repo. Rebuild + redeploy. Re-print and re-file at location B. Forensically wipe the digital copy: `cipher /W:<temp-dir>` + VSS delete (see tasks.md §1.3b). Old snapshots still decrypt with old recovery paper. |
| **R2 token** | Create new token in Cloudflare R2 dashboard. Update `BACKUP_R2_ACCESS_KEY_ID` and `BACKUP_R2_SECRET_ACCESS_KEY` in `scripts/backup/.env.local` and rclone config. Revoke old token. Restart runner. |
| **Gmail App Password** | Revoke in Google account security settings. Generate new App Password. Update `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` in `.env.local` and n8n credential. Restart runner. |
| **Kuma push URL** | In Uptime Kuma, regenerate the push URL for the `MyFinance Daily Backup` monitor. Update `MYFINANCE_BACKUP_KUMA_PUSH_URL` in `.env.local`. Restart runner. |
| **healthchecks.io URL** | Regenerate the check URL in healthchecks.io dashboard. Update `MYFINANCE_BACKUP_HEALTHCHECKS_URL` in `.env.local`. Restart runner. |
| **Runner shared secret** | Generate a new 32-char secret. Update `MYFINANCE_BACKUP_RUNNER_SECRET` in `.env.local` AND in n8n credential. Restart runner. |
| **Pre-op webhook secret** | Generate a new 32-char secret. Update `MYFINANCE_PREOP_WEBHOOK_SECRET` in `.env.local` AND in n8n credential. Restart runner. |

**Printer safety (applies to any key printing):** prefer a USB-connected printer — network printers
cache print jobs in internal storage that can persist long after printing. If only a network
printer is available: after printing, power-cycle the printer AND clear its job history via
the admin UI. The strongest alternative is to hand-copy the key from screen to paper rather
than printing — slower but leaves no printer state. Document which approach was used here so
a future operator picking up the rotation knows the custody chain.

**Notification channels wired to Kuma monitor:** document here which channel(s) the operator
configured in Uptime Kuma (e.g. Telegram bot, Gmail SMTP). A monitor with no wired channel
is silent on failure — the Kuma UI shows "Down" but the operator is never paged.

---

## 2.5.6 Disaster scenarios

| Scenario | Recoverable? | Path |
|---|---|---|
| **All papers AND PC primary identity simultaneously lost** | **UNRECOVERABLE** | This is the only catastrophic key-loss path under the dual-paper design. Both paper and digital copies of the primary identity are gone; the recovery paper is also gone. Every existing encrypted snapshot is permanently unreadable. Mitigation: maintain good paper hygiene (see §2.5.7 annual drill). |
| Primary paper destroyed + PC compromised (primary identity exposed) | YES | Retrieve envelope #2 (recovery identity) from location B. Restore on a clean machine using recovery identity. Generate new key pairs. Rebuild and re-deploy runner. Re-print and re-file new papers. |
| Recovery paper (location B) destroyed | YES | Recover with primary paper (location A) or PC primary identity. Re-generate a new recovery key pair, re-print, re-file at location B immediately. |
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
- [ ] Retrieve envelope #2 (recovery identity) from location B. Confirm the paper is legible and undamaged.
- [ ] On a clean machine, download one snapshot from R2 (`rclone copy r2:my-finance-view-backups/daily/<latest>.tar.age .`).
- [ ] Decrypt with PRIMARY identity: `age -d -i primary-identity-typed.txt -o /tmp/check-primary.tar <snapshot>.tar.age`. Confirm `tar -tf /tmp/check-primary.tar` lists `auth-users.dump`, `myfinance.dump`, `README.txt`.
- [ ] Decrypt same snapshot with RECOVERY identity: `age -d -i recovery-identity-typed.txt -o /tmp/check-recovery.tar <snapshot>.tar.age`. Confirm same file list.
- [ ] Wipe both `/tmp/check-*.tar` and the typed identity files (`shred -u`).
- [ ] If either paper is smudged, water-damaged, or illegible: re-print and re-file BEFORE returning the envelope.
- [ ] Upload drill result to R2:
  ```sh
  cat > /tmp/last-drill.json <<EOF
  {"timestamp":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","papers_intact":{"primary":true,"recovery":true},"reprinted":[],"snapshot_used":"daily/<filename>","operator_initial":"AT"}
  EOF
  rclone copy /tmp/last-drill.json r2:my-finance-view-backups/status/
  rm /tmp/last-drill.json
  ```
  This upload resets the Watchdog's `drill OVERDUE` alert (task 7.4).
- [ ] Return envelopes to their respective locations.
