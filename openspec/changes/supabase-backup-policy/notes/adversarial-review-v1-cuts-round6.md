## Adversarial Review · supabase-backup-policy (Round 6, post round-5 fixes) · 2026-06-02

**Verdict:** PASS

**Scope reviewed:**
- 5 files in working-tree diff (uncommitted): `scripts/backup/workers/backup-daily.sh`, `scripts/backup/workers/backup-preop.sh`, `scripts/backup/workers/alert.sh`, `scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json`, `openspec/changes/supabase-backup-policy/{progress.md,tasks.md}`
- Focus: closure of the five round-5 findings (R5-M1 / R5-M2 / R5-N1 / R5-N2 / R5-N3) and the R5-Q1 cosmetic note
- Lines: roughly +35 / -2 since round 5 (backup-preop.sh +24 ERR-trap skeleton + 6 dispatch + 2 ALERT_DISPATCHED, backup-daily.sh +8 inline dispatch, alert.sh +1 comment line, progress.md +1 entry + last_updated bump, tasks.md +1 new §8.4b)
- Out of scope: operator-only tasks (§1 / §6.2-6.5 / §8 / §9 / §10), pre-existing test-smoke.sh, mojibake, v1-cut decisions, verify-restore.sh (unchanged in R5)

---

### Round-5 finding verification

| # | Finding | Status | Evidence |
|---|---|---|---|
| R5-M1 | pg_isready short-circuit bypassing ERR trap in backup-daily.sh | **FIXED** | `backup-daily.sh:70-79` now calls `dispatch_alert "MyFinance daily backup FAILED — pooler unreachable" "Host ${BACKUP_DB_HOST}:${BACKUP_DB_PORT:-5432} did not respond to pg_isready (5s timeout). Check ..."` then `ALERT_DISPATCHED=1` BEFORE `exit 3`. The set-ALERT_DISPATCHED-before-exit ordering matches the existing SHA-mismatch (line 170) and verify-failed (line 219) patterns. |
| R5-M2 | backup-preop.sh missing ERR trap | **FIXED** | `backup-preop.sh:50-64` installs a symmetric `ALERT_DISPATCHED=0` + `on_error` skeleton with `local exit_code=$?; local line_no=$1; if [[ "$ALERT_DISPATCHED" -eq 1 ]]; then return; fi; ...; dispatch_alert "MyFinance pre-op backup FAILED (line $line_no exit $exit_code)" "reason=${REASON:-unknown} \| $log_tail" \|\| true`. `trap 'on_error "$LINENO"' ERR` at line 64. The pre-op title carries the `reason` for triage which the daily title doesn't need — sensible asymmetry. Inline dispatch points at upload-corrupted (line 146-148) and verify-failed (line 177-179) both set `ALERT_DISPATCHED=1` for parity. The same pg_isready inline-dispatch pattern as backup-daily.sh is at lines 74-80. |
| R5-N1 | progress.md `last_updated` stale | **FIXED** | `progress.md:22` is now `last_updated: "2026-06-02T06:00:00Z"`. Latest commit on the branch is `2026-06-02T00:45:21-05:00` = `2026-06-02T05:45:21Z`. `06:00:00Z` ≥ `05:45:21Z` ✅. New round-5 decision-log entry appended at `progress.md:19`. (The working-tree changes themselves are NOT yet committed, so the `last_updated` ≥ HEAD invariant is what matters — and it holds.) |
| R5-N2 | LF→space comment | **FIXED** | `alert.sh:30-32` now carries the comment `# Collapse LF to single space so the header stays single-line yet the message remains intelligible (the body field carries the multi-line log).` directly above the `title="${title//$'\n'/ }"` substitution. |
| R5-N3 | §8.4b credential rebind step | **FIXED** | `tasks.md:191` adds task 8.4b: "Re-link DispatchAlert HTTP Header Auth credential after import (round-5 review R5-N3): ... Open `MyFinanceBackup-DispatchAlert` → Resend Email Alert node → Credential dropdown → select `MyFinance Backup Resend (HTTP Header Auth)` → Save. Verify by running §9.6 (forced-failure) and confirming the Resend email arrives at the operator inbox. Without this step, n8n-side ErrorHandler dispatches would silently send zero Resend emails (ntfy still fires)." Clear cause, explicit click-path, explicit verify step, and named failure mode. |
| R5-Q1 | `set -E` future-proofing comment | **FIXED (cosmetic)** | `backup-preop.sh:47-48` carries the comment: "Note: `set -E` (errtrace) is intentionally NOT enabled so this ERR trap does not recurse into dispatch_alert (round-5 R5-Q1)." Same intent expressed in backup-daily.sh by the existing `on_error` block + comment header. |

All six R5 items closed.

---

### Operator-question coverage (round-6 prompt)

1. **pg_isready inline-dispatch symmetric between backup-daily.sh and backup-preop.sh?** YES. Both scripts:
   - Use the same `|| { ... exit 3; }` short-circuit shape on pg_isready.
   - Call `log_error` first with the same hostname/port + Supabase Session-pooler hint.
   - Call `dispatch_alert "...pooler unreachable" "Host ... did not respond to pg_isready (5s timeout) ..."` immediately after.
   - Set `ALERT_DISPATCHED=1` BEFORE `exit 3`.
   The only delta is the title ("daily backup" vs "pre-op backup"), which is correct. Verified `backup-daily.sh:70-79` vs `backup-preop.sh:74-80`.

2. **ERR trap interaction with the validate-REASON early-exit at backup-preop.sh:25-28?** No conflict. The validate-REASON branch (`emit_json_result + exit 2`) runs at lines 25-28, which is BEFORE the ERR trap is installed at line 64. So an invalid-reason exit cannot fire the ERR trap regardless of `ALERT_DISPATCHED`. The intent ("invalid reason is a client error, not an alert-worthy failure") is preserved — `emit_json_result` writes the structured error for the runner to surface, and `exit 2` cleanly leaves without dispatching to ntfy/Resend (which would be noise for an operator typo). The ordering "validate args → mktemp + cleanup + on_error → real work" is the correct shape for this kind of script.

3. **§8.4b clarity for a first-time operator?** YES. The task names the cause ("n8n typically generates a UUID at credential-creation time, so on a fresh n8n the imported Resend node may show an empty Credential dropdown"), gives the explicit click-path (workflow name → node name → dropdown → option label → Save), names the verification (§9.6 forced-failure → operator inbox), and names the silent-failure mode if skipped ("ntfy still fires" so the operator could falsely conclude the dispatch is healthy). It mirrors the existing §8.4a's structure so anyone who completed §8.4a will recognise the rhythm. Compare to §8.4a's wording at tasks.md:190 — same level of detail, same "without this step, …" closing.

4. **Round-5 fixes consistent with the existing inline-dispatch pattern (ALERT_DISPATCHED=1 BEFORE exit)?** YES. All four inline-dispatch points across the two scripts follow the identical order:
   - backup-daily.sh:70-79 (pg_isready) — dispatch, set flag, exit
   - backup-daily.sh:165-171 (SHA mismatch) — dispatch, set flag, exit
   - backup-daily.sh:212-220 (verify failed) — dispatch, set flag, exit
   - backup-preop.sh:74-80 (pg_isready) — dispatch, set flag, exit
   - backup-preop.sh:142-150 (upload corrupted) — dispatch, set flag, emit_json_result, exit
   - backup-preop.sh:173-182 (verify failed) — dispatch, set flag, emit_json_result, exit
   The pre-op points have one extra `emit_json_result` step before exit which the daily ones don't, but `ALERT_DISPATCHED=1` always precedes the final `exit N`, so the ERR-trap-no-op guard is honoured.

5. **Other short-circuit patterns (`|| { exit N }`) still bypassing the trap?** None in backup-daily.sh or backup-preop.sh other than the two pg_isready calls already covered by R5-M1 / R5-M2 fixes. `grep -rn '|| {' scripts/backup/workers/` returns only:
   - `backup-daily.sh:70` (fixed)
   - `backup-preop.sh:74` (fixed)
   - `verify-restore.sh:124` — `getent hosts ${VERIFY_CONTAINER} ... || { log_error ... ; exit 4; }` — **out of round-5/6 scope**: verify-restore.sh is invoked as a subprocess by both workers via `if ! printf '%s' "${IDENTITY_CONTENT}" | bash "${VERIFY_SCRIPT}" ...; then VERIFY_FAILED=true; fi`. Its non-zero exit becomes `VERIFY_FAILED=true` in the parent, which then triggers the parent's explicit inline `dispatch_alert + ALERT_DISPATCHED=1 + exit 8` branch (backup-daily.sh:212-220, backup-preop.sh:173-182). So even though verify-restore.sh's own short-circuit doesn't alert from inside verify-restore.sh, the contract "any non-zero verify exit → dispatch_alert" still holds at the parent level. Noted, not a finding.

   Other failure paths in either worker (pg_dump, tar, age, rclone copy, rclone check, rclone copyto, rclone rcat, sha256sum, mkdir) are bare commands that fail under `set -e` and therefore trigger the ERR trap → dispatch_alert. Verified by inspection.

---

### New findings (Round 6)

None.

---

### Acceptance criteria coverage (round-6 deltas)

- ✅ §4.3.14 contract "any failure → dispatch_alert" holds in backup-daily.sh including the pg_isready precheck (R5-M1 fixed).
- ✅ §4.4.x equivalent contract holds in backup-preop.sh (R5-M2 fixed). The pre-op spec doesn't have a numbered "§4.4.X any-failure-dispatches" requirement equivalent to §4.3.14, but the operator-stated invariant ("symmetric with backup-daily.sh") is satisfied.
- ✅ progress.md `last_updated` ≥ latest commit (R5-N1 fixed).
- ✅ CRLF strip on title is now self-documenting (R5-N2 fixed).
- ✅ §8.4b operator rebind step is documented (R5-N3 fixed).
- ✅ `set -E` non-use is now self-documenting in both workers (R5-Q1 fixed).

---

### Scope assessment

- **No scope creep.** All seven file edits since round 5 are directly justified by round-5 findings.
- **No new spec drift.** §8.4b is a procedural addition consistent with the existing §8.4 / §8.4a / §8.5 rhythm; no spec.md / proposal.md / design.md edits were needed for the round-5 closure.
- **Symmetry restored.** backup-daily.sh and backup-preop.sh now share the same ERR-trap skeleton + inline-dispatch pattern + ALERT_DISPATCHED-before-exit invariant. Future readers will not have to re-derive the asymmetry that round-5 caught.

---

### What I tried to break but couldn't

- **Double-dispatch via ERR trap firing after inline dispatch.** Both inline paths (pg_isready, SHA mismatch, verify-failed in daily; pg_isready, upload-corrupted, verify-failed in pre-op) set `ALERT_DISPATCHED=1` BEFORE `exit N`. Even if the explicit `exit N` could somehow re-enter the ERR trap (it cannot — verified by repro in R5), the guard would no-op. Defense-in-depth holds.
- **ERR trap firing with unset LOG_FILE.** In backup-preop.sh, `LOG_FILE` is set at line 66, AFTER the ERR trap at line 64. If `mktemp -d` at line 35 fails, ERR fires before LOG_FILE exists. The `on_error` function guards this with `if [[ -f "${LOG_FILE:-}" ]]`. ✅
- **Validate-REASON exit firing the ERR trap and dispatching a noise alert.** REASON validation runs at lines 25-28 (`exit 2`), BEFORE the ERR trap is installed at line 64. So a typo-reason cannot cause an unwanted ntfy + Resend dispatch. Correct ordering.
- **verify-restore.sh's own `|| { exit 4 }` (DNS not registered) producing a silent failure.** verify-restore.sh is invoked under `if ! ... | bash verify-restore.sh; then VERIFY_FAILED=true; fi` in both workers. A non-zero exit from verify-restore.sh propagates through the pipeline (LHS `printf` succeeds, RHS `bash` exits 4, pipefail captures 4) → the `if !` is true → `VERIFY_FAILED=true` → parent dispatch fires at backup-daily.sh:212-220 / backup-preop.sh:173-182. Contract preserved at the parent level.
- **`set -E` recursive ERR-trap into dispatch_alert.** Neither worker enables `-E`, both now carry an inline comment stating so. If a future edit enables `-E`, the trailing `|| true` on the `dispatch_alert` call inside `on_error` would still mask any non-zero result before it could propagate back into the ERR trap, but the explicit "we do not enable -E" comment removes the temptation to add it without thinking.
- **progress.md last_updated regressing after the round-6 review writes new artefacts.** This review file is a `notes/*` addition that does NOT alter the implementation state captured by `progress.md`. The next commit on this branch SHOULD bump `last_updated` again to cover the new commit timestamp, per the standing harness rule — but that's a forward-looking guidance, not a round-6 finding (the current state at HEAD is consistent).

---

### Verdict justification

**0 Blockers + 0 Majors + 0 Minors + 0 Questions → PASS.**

The round-5 fixes are complete, internally consistent, and symmetric across the two workers. The ERR-trap + inline-dispatch + ALERT_DISPATCHED skeleton now covers every documented failure path in both backup-daily.sh and backup-preop.sh:

- bare `set -e` failures (pg_dump, tar, age, rclone, sha256sum, mkdir) → ERR trap → dispatch_alert.
- pg_isready short-circuit → inline dispatch_alert → ALERT_DISPATCHED=1 → exit 3. ERR-trap-not-fired-on-explicit-exit is harmless because the dispatch already happened.
- SHA mismatch → inline dispatch_alert → ALERT_DISPATCHED=1 → exit 7.
- verify-restore.sh subprocess failure (any non-zero) → parent's VERIFY_FAILED branch → inline dispatch_alert → ALERT_DISPATCHED=1 → exit 8.
- pre-op's upload-corrupted and verify-failed paths additionally emit a structured JSON via `emit_json_result` for the runner to surface, before exit. The ALERT_DISPATCHED flag is set BEFORE both emit_json_result and exit.

§4.3.14 contract is restored. §4.4.x symmetry is restored. tasks.md §8.4b closes the last operator-side gap (silent failure on first credential import). progress.md is fresh.

No remaining gaps to address before `/opsx:archive`. The remaining 35-odd open tasks are operator-driven (§1 prereqs, §6.2 VPS image build, §8 deploy, §9 smoke, §10 activation, §11.5 memory, §12 archive) and are NOT in the round-6 review surface.

Recommend: commit the working-tree diff (`scripts/backup/workers/{backup-daily.sh,backup-preop.sh,alert.sh}` + `openspec/changes/supabase-backup-policy/{progress.md,tasks.md}` + the round-4/5/6 review notes) as a single "fixup(backup): round-5 + round-6 review closure" commit, then archive.
