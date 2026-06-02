## Adversarial Review · supabase-backup-policy (Round 5, post round-4 fixes) · 2026-06-02

**Verdict:** PASS WITH GAPS

**Scope reviewed:**
- 9 files in working-tree diff (uncommitted)
- Focus: round-4 finding closure on workers/alert.sh + workers/backup-daily.sh + n8n/DispatchAlert.json + docs §12.4 + tasks.md honesty + progress.md freshness
- Lines: roughly +120 / -40 since round 4 (alert.sh +56, backup-daily.sh +35 / -28, DispatchAlert.json +5 / -3, dev-guide §12.4 rewrite, tasks.md §8.4 rewrite, progress.md decision-log entry)
- Out of scope: §1 / §6.2-6.5 / §8 / §9 / §10 operator tasks; mojibake; pre-existing test-smoke.sh issues (R3-3)

---

### Round-4 finding verification

#### B1 — alert.sh subshell scoping — **FIXED**

`scripts/backup/workers/alert.sh:93-106` now uses PID-tracked `wait`. Verified by direct bash repro:

```
$ bash -c 'a=0; (exit 0) & pa=$!; (exit 1) & pb=$!; if wait $pa; then a=1; fi; if wait $pb; then b=1; else b=0; fi; echo "a=$a b=$b"'
a=1 b=0
```

Also verified end-to-end by sourcing `alert.sh` with mocked `curl`:

| Scenario (curl mock) | Expected rc | Actual rc | Log line |
|---|---|---|---|
| both `return 0` | 0 | 0 | `INFO Alert dispatched ... ntfy_ok=1 resend_ok=1` |
| ntfy ok, resend `return 22` | 0 | 0 | `INFO Alert dispatched ... ntfy_ok=1 resend_ok=0` |
| both `return 22` | 1 | 1 | `ERROR All alert channels failed for: TestTitle` |
| bad topic `../@evil` (sanity-regex reject) + resend ok | 0 | 0 | `ERROR ... fails sanity regex ... — skipping ntfy alert` then `INFO ... ntfy_ok=0 resend_ok=1` |

The contract documented in the function header (`Returns: 0 if at least one channel succeeded; 1 if both failed`) is now honoured. CLI standalone invocation (`./alert.sh "T" "B"` per lines 118-124) no longer always exits 1 on success.

Note (positive): the `if wait "$pid"` guard does NOT trip `set -e` even when wait returns non-zero. Re-verified.

#### B2 — DispatchAlert credential model — **FIXED (with caveat tracked under N5 below)**

`scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json:64-88` Resend node now uses:

```json
"authentication": "genericCredentialType",
"genericAuthType": "httpHeaderAuth",
...
"credentials": {
  "httpHeaderAuth": {
    "id": "myfinance-backup-resend-auth",
    "name": "MyFinance Backup Resend (HTTP Header Auth)"
  }
}
```

This is a valid n8n HTTP Request node pattern: with `authentication: genericCredentialType` + `genericAuthType: httpHeaderAuth`, n8n auto-injects the credential's `Name`/`Value` (here `Authorization: Bearer <api-key>`) into the HTTP request headers at execution time. No `$credentials.*` expression is required and the Resend API key never appears in the workflow JSON. JSON parses clean (`python -m json.tool` returns OK).

The remaining `$env.MYFINANCE_BACKUP_NTFY_TOPIC` / `$env.MYFINANCE_BACKUP_ALERT_FROM` / `$env.MYFINANCE_BACKUP_ALERT_TO` references at lines 35, 78 are now documented as intentional in `tasks.md §8.4` (n8n container env vars for non-secret config) and in `docs/development-guide.md §12.4`. The B2 root cause — secret-grade values in `$env.*` on a default-hardened n8n — is gone because the Resend bearer no longer travels through `$env.*`.

Caveat carried to N5 below: the `id: "myfinance-backup-resend-auth"` is operator-supplied and n8n typically generates a UUID at credential creation. The operator may have to either (a) edit the imported node post-import to rebind the credential by name, or (b) use the n8n API to create the credential with the documented id. §8.4 should call this out explicitly to avoid silent dispatch failure on first import.

#### M2 — §4.3.14 worker-side "any failure → dispatch_alert" — **FIXED (with one gap raised under R5-M1 below)**

`scripts/backup/workers/backup-daily.sh:43-57` installs `trap 'on_error "$LINENO"' ERR` near the top of the script (after `cleanup` EXIT trap but before LOG_FILE is created). Verified by repro:

```
$ bash -c 'set -euo pipefail; cleanup() { echo EXIT; }; trap cleanup EXIT;
           on_error() { echo "ERR exit=$? line=$1"; }; trap "on_error \$LINENO" ERR;
           false'
ERR exit=1 line=4
EXIT
rc=1
```

Both traps fire, ERR first then EXIT. `LOG_FILE` may be unset when ERR fires between line 57 and line 62 — the `on_error` function guards this with `if [[ -f "${LOG_FILE:-}" ]]`. ✅

ALERT_DISPATCHED guard at lines 164 (SHA-mismatch) and 213 (verify-failed) prevents double-fire on the two inline-dispatch paths. Verified by inspection: both branches set `ALERT_DISPATCHED=1` BEFORE the `exit N`. Even without the guard, explicit `exit N` does NOT trigger ERR trap (verified by repro `set -euo pipefail; on_error(){ echo trapped; }; trap on_error ERR; exit 7` → rc=7, no trap fire), so the guard is defense-in-depth — still correct.

Gap: see R5-M1 below — the `pg_isready ... || { exit 3; }` short-circuit pattern at line 70 still bypasses both the ERR trap and inline dispatch.

#### M3 — backup-daily.sh dead-code re-download — **FIXED**

Confirmed via `git diff`: the old block at the previous lines 119-126 (which used `--local-no-check-updated` on an R2 source, performed a same-path `rclone copy` then a conditional `mv` that risked moving the ORIGINAL encrypted file, then a fallback download to a `redownloaded/` subdir nobody read) is deleted. Only the clean path remains (current lines 152-156): mkdir `${WORK_DIR}/r2-redownload`, `rclone copy r2://... ${REDOWNLOAD_DIR}/`, compare SHAs. The `stat -c%s "${WORK_DIR}/${TODAY}.tar.age"` on the success-JSON path (line 235) now refers unambiguously to the file written by `age -o` at line 129. The `size: 0` regression risk is gone. ✅

#### M4 — task honesty — **FIXED**

§4.2 `[x]` is now honest: alert.sh contract holds (B1 fixed). §4.3.14 `[x]` is now honest: ERR trap covers the "any failure → dispatch_alert" contract for every non-zero exit reaching the trap (gap at pg_isready noted under R5-M1, NOT a §4.3.14 violation since pg_isready does still exit non-zero just without alert). §7.6 `[x]` is now honest: DispatchAlert uses an n8n credential for the secret (B2 fixed) and `$env.*` for non-secret config is documented as intentional in §8.4. ✅

#### N1 — docs §12.4 attribution split — **FIXED**

`docs/development-guide.md:245-263` cleanly splits the two code paths:

> 1. **`workers/alert.sh`** ... Inline `dispatch_alert` calls cover the SHA-mismatch quarantine path and the verify-failed quarantine path; the script-level `ERR` trap (round-4 review M2 fix) catches every other non-zero exit ...
> 2. **`scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json`** (an n8n sub-workflow) fires on n8n-side failures via the `MyFinanceBackup-ErrorHandler` workflow's Error Trigger ...

The credential storage model (HTTP Header Auth for Resend bearer; n8n container env vars for ntfy topic + from + to; sidecar `.env.local` for the worker side) is documented at lines 254-256 with a back-reference to `tasks.md §8.4`.

#### m1 — ntfy topic sanity regex — **FIXED**

`alert.sh:43-46`. Verified above (bad topic `../@evil` is rejected with the documented error and Resend still fires).

#### m2 — CRLF strip on title — **FIXED**

`alert.sh:30-32` runs `title="${title//$'\r'/}"; title="${title//$'\n'/ }"` before any header construction. Verified by sourcing alert.sh with a CRLF-injected title — the log shows `badinj: evil title` (CR removed, LF → space).

---

### New findings (Round 5)

| # | Severity | Area | Evidence | Recommended fix |
|---|---|---|---|---|
| R5-M1 | Major | Workers / backup-daily.sh | `scripts/backup/workers/backup-daily.sh:70-73` — `pg_isready ... || { log_error "..."; exit 3; }` short-circuits with explicit `exit 3` inside the `||` clause. Explicit `exit N` does NOT trigger the ERR trap (verified). Net effect: pooler-unreachable failures exit non-zero WITHOUT firing `dispatch_alert`, re-introducing the original §4.3.14 gap for this one path. Same pattern at `backup-preop.sh:47-50`. Round-4 M2's claimed-fix is therefore PARTIAL. | code: either (a) drop the `|| { exit 3; }` wrapper and let `set -e` propagate the pg_isready exit code naturally so ERR fires, OR (b) call `dispatch_alert "MyFinance daily backup FAILED — pooler unreachable" "..."` inside the `||` clause and set `ALERT_DISPATCHED=1` before `exit 3`. |
| R5-M2 | Major | Workers / backup-preop.sh | `scripts/backup/workers/backup-preop.sh` has NO `ERR` trap installed. Pre-op failures from `pg_isready` (line 47), `pg_dump` (lines 56, 63), `tar` (line 81), `age` (line 88), `rclone` (lines 100-101, 109) all exit non-zero without firing `dispatch_alert`. Pre-op is operator-triggered and currently relies entirely on the n8n PreOp workflow's `errorWorkflow: MyFinanceBackup-ErrorHandler` to alert — which itself depends on §8.4a being completed. Inconsistent with backup-daily.sh post-round-4 fix; the user explicitly raised this in the round-5 prompt. | code: install the same ERR trap pattern in backup-preop.sh; reuse the same `ALERT_DISPATCHED=0` + `on_error` skeleton. The two existing inline `dispatch_alert` callsites (upload-corrupted line 116, verify-failed line 146) should set `ALERT_DISPATCHED=1` for symmetry. |
| R5-N1 | Minor | process-tooling / progress.md | `openspec/changes/supabase-backup-policy/progress.md:21` — `last_updated: "2026-06-02T05:30:00Z"` is older than the most recent commit on the branch (`git log -1 --format=%cI` → `2026-06-02T05:45:21Z` for commit 261d507). Per CLAUDE.md harness rule + `harness-progress-tracking/design.md` Decision 2, a stale `last_updated` silently misleads the next session. | rewrite progress.md to reflect the latest task state before archive — bump `last_updated` to a timestamp ≥ the most recent commit; append the round-5 decision-log entry after the round-4 entry; bump `last_completed` if any closed task has shifted. |
| R5-N2 | Minor | Workers / alert.sh | `alert.sh:32` — `title="${title//$'\n'/ }"` replaces LF with one space; `title="${title//$'\r'/}"` strips CR. Net result on input `bad\rinj: evil\ntitle` is `badinj: evil title` (two-spaces collapsed to one since there's only one LF). OK and intentional, but the original round-4 m2 recommended fix had a typo `"${title//$'\n'/ / }"` (extra slash) and the author silently corrected it to `' '`. Worth a one-liner comment that LF→space is the documented behaviour, otherwise a future reader will assume the intent was "strip both". | code: add `# Collapse LF to single space so the header stays single-line yet the message remains intelligible.` |
| R5-N3 | Minor | n8n / DispatchAlert credentials | `scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json:85` — `"id": "myfinance-backup-resend-auth"` is an operator-supplied static ID. n8n typically generates a UUID at credential-creation time; the import process may either (a) silently rebind by name OR (b) leave the node with a dangling reference until manually re-selected. The `tasks.md §8.4` line documents this id but does not state the post-import re-link step (the way §8.4a documents errorWorkflow rebinding). | docs/tasks: add a §8.4b note: "After importing DispatchAlert, open the Resend Email Alert node and confirm the Credential dropdown shows `MyFinance Backup Resend (HTTP Header Auth)`. If empty, re-select it and Save. Verify by triggering §9.6 (forced-failure)." |
| R5-Q1 | Question | Workers / alert.sh `dispatch_alert` failure mode | The ERR trap calls `dispatch_alert "..." "..." || true`. If `dispatch_alert` itself fails (both ntfy + Resend down), the `\|\| true` masks the return code from ERR-trap-re-entry. Good. But what if the operator runs the script under `set -E` (errtrace) in the future? Then the ERR trap would inherit into the dispatch_alert function and potentially fire recursively. Current code uses `set -euo pipefail` without `-E`, so this is theoretical. Worth a defensive `set +E` inside `on_error` or a comment. | Decide: add a one-line comment in `on_error` stating "`set -E` is intentionally NOT enabled so this trap does not recurse into dispatch_alert"; OR no-op if the operator confirms they will never enable `-E`. |

---

### Acceptance criteria coverage (round-5 deltas)

- ✅ alert.sh at-least-one-succeeds contract restored (B1 fixed).
- ✅ DispatchAlert uses an n8n credential for the Resend bearer; `$env.*` for non-secret config is documented as intentional (B2 fixed).
- ✅ backup-daily.sh ERR trap restores §4.3.14 contract for the majority of failure surface (M2 fixed, except R5-M1 pg_isready gap).
- ✅ backup-daily.sh dead-code re-download block removed; success-JSON `size` is no longer at risk of being 0 (M3 fixed).
- ✅ tasks.md §4.2 / §4.3.14 / §7.6 `[x]` flips now match implementation reality (M4 fixed).
- ✅ docs §12.4 split the two alerting code paths (N1 fixed).
- ✅ ntfy topic sanity regex + CRLF strip landed (m1 + m2 fixed).
- ❌ backup-preop.sh has no ERR trap — symmetric with backup-daily.sh pre-fix (R5-M2 — NEW).
- ❌ pg_isready `|| { exit 3; }` short-circuit bypasses ERR trap (R5-M1 — NEW, narrows the M2 fix).
- ❌ progress.md `last_updated` stale relative to latest commit (R5-N1 — NEW).

---

### Scope assessment

- **No scope creep** in the round-5 patch: every edit is justified by round-4 findings (B1 / B2 / M2 / M3 / M4 / N1 / m1 / m2) or by the tasks.md §8.4 credential-model rewrite.
- **No spec drift** between code and docs. §12.4 and §8.4 agree on the credential model (HTTP Header Auth for Resend; n8n container env for ntfy/from/to; sidecar env for the worker side).
- **Implementation gaps, not scope gaps:** R5-M1 narrows the M2 fix; R5-M2 extends the M2 contract to backup-preop. Neither requires a new spec section.

---

### What I tried to break but couldn't

- **`set -e` + `if wait "$pid"`.** Verified the `if`-condition exempts a failing `wait` from `set -e`. The function does NOT exit early when one channel fails; both PIDs are always waited and the rc is computed at the end. ✅
- **EXIT + ERR trap interaction.** Verified ERR fires before EXIT; both fire on `false`; explicit `exit N` skips ERR but still runs EXIT cleanup. The `${WORK_DIR}` is always cleaned up. ✅
- **Recursive ERR trap via dispatch_alert failing inside `on_error`.** `dispatch_alert ... || true` masks the failure; `set -E` is not enabled so the ERR trap is not inherited by the dispatch_alert function call. No recursion. ✅
- **DispatchAlert JSON malformedness.** Parses cleanly. The `credentials` block has the correct n8n shape for HTTP Header Auth (`type: httpHeaderAuth`, `id` + `name` pair). ✅
- **Identity tmpfs leakage in verify-restore.sh.** Unchanged in round-5; previously verified the umask 0177 + EXIT/INT/TERM trap that `shred -u`s the identity. ✅
- **alert.sh standalone CLI mode.** `./alert.sh "T" "B"` now returns 0 when at least one channel succeeds (was: always 1 pre-round-5). ✅
- **JSON-body injection via title in Resend payload.** `jq -nc --arg subject "$title" ... '{... subject: $subject ...}'` correctly escapes any character. ✅
- **CRLF header injection on ntfy `Title:` header.** Verified the `${title//$'\r'/}` + `${title//$'\n'/ }` substitutions strip CR and replace LF with a space before header construction. ✅

---

### Verdict justification

**0 Blockers + 2 Majors + 3 Minors + 1 Question → PASS WITH GAPS.**

All round-4 findings landed correctly. The alert.sh wait-PID rewrite is sound and verified; the DispatchAlert credential model is a sensible pragmatic shape (HTTP Header Auth for the secret, container env for non-secrets) and JSON-parses clean; the backup-daily.sh ERR trap restores the §4.3.14 contract for the bulk of the failure surface; the dead-code re-download is gone; the docs and tasks honestly reflect the new state.

Two gaps remain that should be addressed before `/opsx:archive`:

1. **R5-M1** — the `pg_isready ... || { exit 3; }` short-circuit at backup-daily.sh:70 (and backup-preop.sh:47) skips the new ERR trap. Pooler-unreachable will exit non-zero without an alert — exactly the §4.3.14 gap M2 set out to close, for this one path. Either drop the `|| { exit 3 }` wrapper or dispatch + set ALERT_DISPATCHED before exit.
2. **R5-M2** — backup-preop.sh has no ERR trap at all. The user explicitly flagged this asymmetry in the round-5 prompt; symmetry with backup-daily.sh is cheap (~10 lines, copy the on_error skeleton) and removes a real silent-failure surface.

Neither blocks ship in the strict sense — pre-op is operator-driven and the n8n PreOp workflow's `errorWorkflow` covers most cases once §8.4a is completed — but together they're a half-applied M2 fix that future reviewers will re-raise. Address before archive.

Minors R5-N1 (progress.md staleness), R5-N2 (LF→space comment), R5-N3 (credential rebind step in §8.4) and Question R5-Q1 (set -E future-proofing) are cleanups.
