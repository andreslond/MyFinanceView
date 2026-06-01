# Supabase backup pipeline — multi-tenant + n8n state design

**Status:** brainstorm output, awaiting Phase 2 OpenSpec proposal.
**Date:** 2026-05-31
**Author:** operator + Claude (brainstorming session).
**Related:** `openspec/changes/supabase-backup-policy/` (Phase 1, MFV single-tenant, currently being replanted).

---

## Why this document

The in-flight `supabase-backup-policy` change scopes a backup pipeline for a single
project (MyFinanceView). During the v3 replant, two new requirements surfaced from
the operator:

1. The same backup pipeline must serve **other Supabase projects** (Nomina,
   SignSystem, and future ones) — configurable and extensible.
2. The **n8n instance itself** must be backed up so it can be restored after an
   attack, host outage, or data loss event.

A brainstorming session explored four architectural paths (sidecar Node+HTTP,
n8n+tools-sibling via docker exec, n8n SSH to host, cron-internal sidecar) and
reconfirmed Path A (sidecar Node+HTTP) as the right architectural foundation —
primarily because it is the only path that **isolates the Docker socket to a
single-purpose container** rather than exposing it to n8n (which has a larger
attack surface: web UI, public webhooks, known CVE history).

This document captures:
- The honest comparison of paths considered (so future-us doesn't re-litigate).
- The multi-tenant extension shape (Phase 2).
- The n8n state backup shape (Phase 2).
- The two-phase rollout: land Phase 1 (single-tenant MFV), then propose Phase 2.

---

## Architectural path comparison (decision record)

The four paths considered, all targeting the goal "fewer services, less code,
multi-project capable":

| Path | Who triggers backups | Docker socket location | Code surface | Verdict |
|---|---|---|---|---|
| **A — sidecar Node + HTTP** | n8n → HTTP → sidecar | sidecar only | ~1100 LOC (Node + bash) + 370 npm transitive | **CHOSEN** — socket isolation outweighs Node cost |
| **B — tools sibling, docker exec** | n8n → docker exec → tools | n8n AND tools (socket in 2 containers) | ~700 LOC bash | Rejected — widens n8n blast radius |
| **C — bash sidecar, n8n via docker exec** | n8n → docker exec → sidecar | same as B (socket required in n8n) | ~700 LOC bash | Rejected — same as B, was a mis-framed option in the brainstorm |
| **D — cron internal in sidecar, n8n watches** | sidecar cron, autonomous | sidecar only | ~700 LOC bash | Rejected — loses PreOp on-demand from n8n UI |
| **E — shared filesystem trigger** | n8n writes trigger file, sidecar polls | sidecar only | ~700 LOC bash + polling | Rejected — clunky, no synchronous response |
| **F — n8n SSH to host, host runs docker** | n8n SSH → host → docker exec | host (not in n8n) | ~700 LOC bash + SSH key mgmt | Rejected — SSH key from container to host is more friction than HTTP |

### Why Path A despite the Node cost

The operator's threat model (Phase 1 proposal.md):
- Single trusted operator, no multi-user scenario.
- n8n is on a private VPS behind Traefik (not internet-exposed admin UI).
- Local-forensic adversary explicitly out of scope.

Under this model, the marginal security delta between A and B/C is small. However:

- **n8n has the largest blast radius if compromised.** It holds ALL credentials
  (DB passwords, R2 tokens, AGE identities, alert channel secrets) for every
  project in the matrix. Giving n8n the Docker socket on top of that means a
  single n8n CVE escalates to root-on-host.
- **The sidecar is single-purpose.** It does not process untrusted webhooks, has
  no admin UI, runs no LLM-generated code. The probability of a sidecar
  compromise that the n8n compromise wouldn't have triggered first is low.
- **Operator already accepted the Node maintenance cost** in v3 — the replant
  preserves a well-tested codebase (auth.test.js, mutex.test.js, server.js with
  pino redaction, B7-fix identity-via-stdin pattern).

The Node cost is real (~600 LOC, npm dependency tree, Jest + Helmet + Express
versioning) but bounded. The socket isolation is a structural property that no
amount of discipline can recover in path B/C.

---

## Phase 1 — single-tenant MFV (in flight)

**Scope:** `openspec/changes/supabase-backup-policy/` (v3 as cherry-picked from
`supabase-cuts`, with implementation replanted on
`feat/supabase-backup-policy-replant`).

**Status at 2026-05-31:** 82 of 126 tasks ticked. Remaining are operator
prereqs (§1: install age, R2 token, lifecycle policies, Gmail App Password,
ntfy topic, Kuma push URL, Supabase password, runner shared secret) and VPS
deployment (§8-§12: docker compose up, import workflows, smoke tests,
activation, docs+memory updates, archive).

**Phase 1 explicitly does NOT include:**
- Multi-tenancy. Workers and workflows are hardcoded to MFV.
- n8n state backup. Only MFV's Supabase data is covered.
- Any verify probes outside `myfinance` schema + `auth.users`.

**Rationale for landing Phase 1 first:**
- Phase 1 work is 90% complete; the marginal cost to ship it is small.
- MFV gains real backup protection before Flyway baseline runs.
- Phase 2 refactor benefits from a working Phase 1 in production as the
  reference implementation (operator has seen the real flow, end-to-end).
- Atomic OpenSpec changes are easier to review and archive cleanly.

---

## Phase 2 — multi-tenant + n8n state extension

**Scope (proposed):** new OpenSpec change `supabase-backup-multi-tenant` that
refactors the sidecar + workers to accept per-project config and adds support
for n8n's own state as a backed-up "project".

### 2.1 Sidecar changes (Node)

**Endpoints:** `POST /run/daily` and `POST /run/preop` accept a JSON body with
per-project config:

```json
{
  "project": "nomina",
  "dbUrl": "postgresql://...",
  "r2Bucket": "nomina-backups",
  "r2Prefix": "",
  "ageRecipientPath": "/opt/myfinance-backup/recipients/nomina/primary.txt",
  "verifyQueriesPath": "/opt/myfinance-backup/verify-queries/nomina.sql",
  "ntfyTopic": "<from n8n credential>",
  "gmailTo": "aftorresl01@gmail.com",
  "schemas": ["nomina_app"],
  "extraTables": ["auth.users"]
}
```

The AGE secret continues to flow via stdin pipe — never in the HTTP body and
never in env vars (preserves Phase 1's B7 fix).

`/status` accepts `?project=<name>` (returns single-project view) or no param
(returns aggregated map of all projects). The R2 read goes against the
project-specific bucket (or skips projects whose bucket is unset).

**Mutex** evolves from in-process boolean to per-project map:
```javascript
const _locks = new Map(); // project → boolean
function acquireRunLock(project) { ... }
```
Different projects can run in parallel (they hit different DBs, different R2
buckets). Same-project concurrent runs return 409.

### 2.2 Bash worker changes

All workers already read config from env vars; Phase 2 makes the remaining
hardcoded constants parametric:

- `RECIPIENTS_DIR` → derived from `AGE_RECIPIENT_PATH` env var.
- `BUCKET` → from `BACKUP_R2_BUCKET` (already env-driven).
- Verify queries → `VERIFY_QUERIES_PATH` (default to project-specific file).
- `auth.users` dump → optional (`EXTRA_TABLES=auth.users` or
  `EXTRA_TABLES=""`); some projects may not use Supabase Auth.

New worker: `workers/backup-n8n.sh` (see §2.4).

### 2.3 n8n workflow refactor

Pattern: **Execute Workflow with a shared sub-workflow**.

```
BackupCore (sub-workflow, NOT directly scheduled):
   Trigger: Execute Workflow
   Inputs: {project, mode, reason?}
   Logic:
     1. Switch/IF on `project` → maps to credential bundle:
        - mfv:        {MFV_DB, MFV_R2, MFV_AGE, MFV_NTFY}
        - nomina:     {NOMINA_DB, NOMINA_R2, NOMINA_AGE, NOMINA_NTFY}
        - signsystem: {SIGNSYSTEM_DB, SIGNSYSTEM_R2, SIGNSYSTEM_AGE, SIGNSYSTEM_NTFY}
        - n8nstate:   {N8NSTATE_DB, N8NSTATE_R2, N8NSTATE_AGE, N8NSTATE_NTFY}
     2. Construct HTTP body with the resolved config.
     3. POST sidecar:/run/<mode> with the body + identity-stdin.
     4. On non-2xx → throw (parent ErrorHandler fires).
     5. Return sidecar response.

MFV-Daily:        Schedule 02:30 BOG → Set {project:"mfv",   mode:"daily"} → Execute BackupCore
Nomina-Daily:     Schedule 02:35 BOG → Set {project:"nomina",mode:"daily"} → Execute BackupCore
SignSystem-Daily: Schedule 02:40 BOG → Set {project:"signsystem",mode:"daily"} → Execute BackupCore
n8nState-Daily:   Schedule 02:45 BOG → Set {project:"n8nstate",mode:"daily"} → Execute BackupCore

MFV-PreOp:        Manual Trigger → Set Reason → Validate → Execute BackupCore(mode:"preop")
(... one PreOp wrapper per project that needs on-demand snapshots)

Watchdog (one for all):
   Schedule 09:00 BOG → loop [mfv, nomina, signsystem, n8nstate] →
                         HTTP GET sidecar:/status?project=X →
                         IF lastSuccess>30h OR lastVerify>30h OR lastDrill>400d →
                         DispatchAlert with project name in title
```

**Schedule staggering**: 5-minute offsets prevent all backups from hitting the
sidecar at once. Each backup takes 1-10 minutes (depending on dataset); 5
minutes is enough margin for small projects and lets verify-restore complete
between starts.

### 2.4 n8n state backup specifics

n8n persists state in two places:
- **Postgres** (recommended; `DB_TYPE=postgresdb`). Schema usually `public`,
  database `n8n`. Lives on a local Postgres container on the VPS.
- **`/home/node/.n8n/`** directory inside the n8n container: config,
  `encryptionKey` (CRITICAL — without it credentials are unrecoverable),
  potentially `database.sqlite` if not using Postgres.

`workers/backup-n8n.sh` does:

1. `docker exec` the n8n Postgres container: `pg_dump -Fc -Z 9 -d n8n` →
   `n8n-db.dump`.
2. `docker exec` the n8n container: `tar -cf - /home/node/.n8n` →
   `n8n-files.tar` (streams to sidecar via stdout pipe).
3. Bundle `n8n-db.dump` + `n8n-files.tar` into `snapshot.tar` (with README.txt).
4. age-encrypt with `recipients/n8nstate/primary.txt`.
5. rclone copy to `r2:n8nstate-backups/daily/`.
6. SHA-256 re-verify post-upload (M12 fix from Phase 1, preserved).
7. Optional chained verify-restore — n8n state verify is more complex than DB
   verify (would need to boot a temporary n8n container and check it starts).
   **Phase 2 deferred decision:** include n8n verify chain or only check that
   the dump+tar restore cleanly without booting n8n. Recommendation: latter.

Recovery scenario for n8n:
1. Restore Postgres DB from `n8n-db.dump`.
2. Restore `/home/node/.n8n/` directory from `n8n-files.tar` into a fresh n8n
   container.
3. Critical: the `encryptionKey` in the restored config MUST match what was
   used to encrypt the credentials in the restored DB. They come from the same
   snapshot, so this is automatic — but document explicitly because it is
   non-obvious.
4. Boot n8n; verify workflows list loads and credentials decrypt.

### 2.5 R2 layout

**Per-project buckets** (decided):

- `mfv-backups/{daily,weekly,monthly,pre-op,quarantine,status}/`
- `nomina-backups/{daily,weekly,monthly,pre-op,quarantine,status}/`
- `signsystem-backups/{daily,weekly,monthly,pre-op,quarantine,status}/`
- `n8nstate-backups/{daily,weekly,monthly,pre-op,quarantine,status}/`

Each bucket has its own:
- R2 API token (scoped to that bucket only — limits blast radius).
- Lifecycle policies (5 prefixes × 4 buckets = 20 policies total).
- rclone remote config (`[r2-mfv]`, `[r2-nomina]`, etc.) OR a single `[r2]`
  remote and the bucket name passed per-call.

### 2.6 Operator setup per new project

To add a project to the backup matrix (one-time, ~30 min):

1. **R2:** create bucket `<project>-backups`, create API token scoped to it,
   configure 5 lifecycle policies.
2. **age:** generate `recipients/<project>/primary.txt` + matching identity;
   print paper, file at location A.
3. **n8n credentials:** create bundle `<PROJECT>_DB`, `<PROJECT>_R2`,
   `<PROJECT>_AGE`, `<PROJECT>_NTFY`, `<PROJECT>_GMAIL_TO`.
4. **n8n workflows:** duplicate `MFV-Daily.json` → `<Project>-Daily.json`,
   edit the Set node values. (Optional: duplicate PreOp.json the same way.)
5. **Sidecar:** rebuild image to bake the new recipient file (one-time per
   added project, or use a runtime volume mount for recipients to avoid
   rebuild).
6. **Watchdog:** add the project to the loop array in `Watchdog.json`.
7. **Smoke test:** trigger the new workflow manually, confirm daily/ landing
   and verify probes pass.

---

## Open questions for Phase 2 proposal

1. **Recipients: baked into image vs runtime mount?**
   Baking is simpler but requires image rebuild per project added. Mounting
   `recipients/` as a docker volume allows zero-rebuild project addition but
   adds an operational concern (volume content matches recipient identity).
   Recommend: baked for v1 (slow project-addition cadence is fine), revisit
   if monthly project additions become common.

2. **Per-project verify probes — operator-authored or templated?**
   MFV has 4 probes (transactions ≥300, accounts ≥1, categories ≥19,
   auth.users ≥1). Nomina/SignSystem will need their own thresholds. Should
   each project ship a `verify-queries/<project>.sql` file (operator writes
   based on knowledge of the schema), or a generator that introspects
   `information_schema.tables` and tests `count(*) >= 1` per table?

3. **n8n verify chain — included or deferred?**
   Booting a temporary n8n container to verify the state restore works is
   meaningful but heavy (n8n image is ~500MB, boot time ~30s, requires test
   workflows that don't depend on production credentials). Recommend deferring
   to a Phase 2.1 / Phase 3.

4. **PreOp wrappers per project — needed for all projects, or only MFV?**
   PreOp matters when a project is about to execute a write (Flyway migrate).
   MFV will use it before Flyway baselines. Nomina and SignSystem may not need
   it if their write cadence is different. Recommend: ship PreOp only for
   projects that explicitly need it; don't blanket-add to all.

5. **Mutex granularity — per-project or per-bucket?**
   Per-project (the proposal above) allows MFV daily + Nomina daily in parallel
   (different DBs, different buckets). Per-bucket would be the same in practice.
   Per-sidecar (global lock) is simpler but serializes all backups. Recommend
   per-project as proposed.

6. **Schedule staggering — fixed offsets or dynamic queue?**
   Fixed offsets (02:30, 02:35, 02:40, 02:45) are simple but break if a backup
   takes >5 minutes (next one queues at the sidecar). A dynamic queue inside
   the sidecar (already exists via per-project mutex — concurrent attempts get
   409) handles this without operator concern. Recommend fixed offsets +
   accept that 409 retry semantics exist on the n8n side.

---

## Two-phase rollout plan

### Phase 1 (current, in flight on `feat/supabase-backup-policy-replant`)

1. Operator completes `supabase-backup-policy/tasks.md` §1 (prereqs) +
   §8-§10 (deploy + smoke + activate) + §11 (docs).
2. Adversarial-review the replant before archive.
3. `/opsx:archive supabase-backup-policy` + `/openspec-sync-specs`.
4. MFV has working single-tenant backups in production.

### Phase 2 (open after Phase 1 archives)

1. Operator triggers `/opsx:explore` or `/opsx:propose` with this design doc
   as input.
2. New change: `supabase-backup-multi-tenant`.
3. Tasks:
   - Refactor sidecar `server.js` + `workers.js` to accept per-project config.
   - Refactor `backup-daily.sh`, `backup-preop.sh`, `verify-restore.sh` to
     parameterize all remaining hardcoded constants.
   - Add `workers/backup-n8n.sh`.
   - Add `verify-queries/<project>.sql` per project.
   - Add `recipients/<project>/primary.txt` per project.
   - Refactor n8n workflows: split into `BackupCore.json` (sub-workflow) +
     per-project wrappers.
   - Operator §1 prereqs for Nomina + SignSystem + n8nstate (3× R2 bucket +
     3× age key + 3× n8n credential bundle).
   - Smoke tests per project.
   - Docs update.
4. Adversarial-review + archive.

---

## What this document does NOT include

- A line-by-line refactored `server.js` — that lives in the Phase 2 OpenSpec
  proposal once opened.
- Verify probe definitions for Nomina, SignSystem, n8nstate — operator must
  define based on schema knowledge.
- R2 cost projection at 4 projects — Phase 1 estimated <$0.10/month for one
  project (single-user ~100MB); 4 projects ~$0.40/month, still inside the
  10GB free tier with room to spare.
- Migration of EXISTING MFV backups when Phase 2 lands — Phase 2 should be
  backward-compatible (single-project mode remains valid) so existing
  `mfv-backups` bucket continues to be written to.

---

## References

- `openspec/changes/supabase-backup-policy/proposal.md` (Phase 1 scope)
- `openspec/changes/supabase-backup-policy/design.md` (Phase 1 architecture)
- `openspec/changes/supabase-backup-policy/tasks.md` (Phase 1 task tracker)
- `scripts/backup/` (Phase 1 implementation)
- `plans/2026-05-31-monorepo-restructure-design.md` (monorepo context for
  paths used above)
