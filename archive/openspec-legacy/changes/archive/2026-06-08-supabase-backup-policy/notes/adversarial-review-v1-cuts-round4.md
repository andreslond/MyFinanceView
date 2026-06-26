## Adversarial Review · supabase-backup-policy (Round 4, post /opsx:apply) · 2026-06-02

**Verdict:** FAIL

**Scope reviewed:**
- 8 files in working-tree diff (uncommitted)
- Focus: workers/alert.sh + workers/backup-daily.sh + n8n/DispatchAlert + docs §12 cross-refs + tasks.md `[x]` honesty
- Lines: +185 / −244 (mostly the Watchdog deletion)
- Out of scope: operator-only §1/§6.2-6.5/§8/§9/§10 tasks; mojibake; v1-cut decisions themselves

---

### Workers code review

#### alert.sh

**B1 (Blocker) — subshell scoping breaks "at-least-one-succeeds" semantics.**
`scripts/backup/workers/alert.sh:79-81`:
```bash
send_ntfy   && ntfy_ok=1   || true &
send_resend && resend_ok=1 || true &
wait
```
The `&` puts each compound command in a **child shell**. The `ntfy_ok=1` / `resend_ok=1` assignments happen in those child shells, never in the parent. After `wait`, the parent's `ntfy_ok` and `resend_ok` remain at their initial value 0 **regardless of outcome**. Verified by direct shell repro:
```
$ a=0; b=0; true && a=1 || true & true && b=1 || true & wait; echo "$a $b"
0 0
```
Concrete impact:
- Line 83's `if [[ $ntfy_ok -eq 0 && $resend_ok -eq 0 ]]` is **always true**, so `log_error "All alert channels failed for: ${title}"` fires on every single dispatch — including healthy ones.
- The function returns 1 on every call.
- The "at-least-one-succeeds" contract documented in the header (`Returns: 0 if at least one channel succeeded; 1 if both failed`) is inverted to "always 1".
- Today's callsites mask this with `|| true` (e.g. `backup-daily.sh:138`, `backup-daily.sh:186`, `verify-restore.sh:227`, `backup-preop.sh:117`, `backup-preop.sh:147`) so the worker doesn't crash. But: (a) every alert run leaves a misleading `ERROR All alert channels failed` line in `/var/lib/myfinance-backup/logs/<runId>.log`, eroding the operator's ability to triage real failures; (b) `alert.sh` as a standalone CLI (`./alert.sh "title" "body"` per lines 92-99) **exits 1 on success**, which would break any direct-invocation contract; (c) the contract is fragile to refactor — any future caller that checks the return code without `|| true` would treat every health-OK alert as catastrophic.

Fix (one of):
- Run each branch in a subshell whose **exit code** carries the result, then check exit codes after `wait`:
  ```bash
  send_ntfy &
  ntfy_pid=$!
  send_resend &
  resend_pid=$!
  wait "$ntfy_pid"   && ntfy_ok=1   || ntfy_ok=0
  wait "$resend_pid" && resend_ok=1 || resend_ok=0
  ```
- Or write results to temp files inside each child and read them after `wait`.

Either way, add a unit test in `test-smoke.sh` that asserts `dispatch_alert "T" "B"` returns 0 when at least one channel is reachable.

**M1 (Major) — `MYFINANCE_BACKUP_NTFY_TOPIC` value attacker-controlled into URL.**
`scripts/backup/workers/alert.sh:40`:
```bash
curl -fsSL --max-time 15 \
  -X POST "https://ntfy.sh/${topic}" \
```
The env var is unvalidated. If the operator (or a future supply-chain attacker) ever sets it to a value containing `..` or `@` or whitespace, `curl` will happily redirect to a different host. Low likelihood given v1 threat model (the topic is operator-controlled), but task §1.7 says "32+ chars, mix of letters and digits" — a sanity regex `^[A-Za-z0-9_-]{16,}$` cheaply enforces the contract. Recommend adding a guard alongside the existing emptiness check at line 36.

**Minor — `log_error "...skipping ntfy alert"` runs in subshell.**
`scripts/backup/workers/alert.sh:36-37`: the `log_error` and `return 1` inside `send_ntfy` (called inside `&` per B1) writes to the parent stderr (because file descriptors are inherited), which is fine for visibility, but the return value is what feeds the subshell-bug at B1. Once B1 is fixed, this is OK.

#### backup-daily.sh

**M2 (Major) — Task §4.3.14 marked `[x]` but only partially implemented.**
The task says: *"On any failure (any step above exiting non-zero through `set -euo pipefail`), capture last 20 log lines, call `dispatch_alert "MyFinance backup FAILED" "$LOG_TAIL"`, exit non-zero."*
The script's own comment at `backup-daily.sh:248-253` admits the gap:
> "Note: this function is defined but EXIT trap above (cleanup) runs on all exits. **Alert dispatch for failures is handled inline at each failure point above.** The trap only cleans up the working directory."

Inline `dispatch_alert` is only wired at two locations (line 137 SHA mismatch, line 185 verify failed). Failures from:
- `pg_isready` exit 3 at line 42 (covered with `||` but bare `exit 3`, no alert)
- `pg_dump auth.users` at line 51 (set -e kills the script, no alert)
- `pg_dump myfinance` at line 61 (same)
- `tar` at line 92 (same)
- `age` encrypt at line 99 (same)
- `sha256sum` / `rclone copy` / `rclone check` at lines 108-116 (same)
- `rclone copy` re-download at lines 123, 125, 130 (same)
- `rclone rcat` for status/last-success.json at line 217 (same)
- `rclone rcat` status.log append at lines 223-226 (same)

…all bypass `dispatch_alert`. The script exits non-zero, Node returns 500 to n8n, and **the n8n `errorWorkflow: MyFinanceBackup-ErrorHandler` is what produces the alert in those cases.** That is arguably a sufficient safety net, but: (a) it is contingent on the operator completing §8.4a (re-linking errorWorkflow after import), which is still `[ ]` and is a documented n8n gotcha; (b) the task spec explicitly required worker-side alert dispatch, not just n8n-side; (c) if n8n itself is the failing component (e.g. silent Schedule-Trigger non-fire — the very gap §1.10 admits), no alert fires at all.

Fix (one of):
- Install an `ERR` trap that calls `dispatch_alert "MyFinance backup FAILED step at $LINENO" "$(tail -20 "$LOG_FILE")"` before exit, restoring the §4.3.14 contract.
- Or revise the task wording to acknowledge "early failures alert via n8n ErrorHandler; worker only alerts after upload-step boundary" and re-flip §4.3.14 to `[ ]` with that as a deferred subtask.

The flipped `[x]` without either path is task-completion dishonesty.

**M3 (Major) — dead/contradictory code at lines 119-125.**
`scripts/backup/workers/backup-daily.sh:119-125` (re-download for SHA-256 verify):
```bash
log_info "Re-downloading for SHA-256 verification"
REDOWNLOAD_PATH="${WORK_DIR}/redownload-${TODAY}.tar.age"
rclone copy "r2:${BUCKET}/daily/${TODAY}.tar.age" "${WORK_DIR}/" --local-no-check-updated
mv "${WORK_DIR}/${TODAY}.tar.age" "${REDOWNLOAD_PATH}" 2>/dev/null || \
  rclone copy "r2:${BUCKET}/daily/${TODAY}.tar.age" "${WORK_DIR}/redownloaded/"
```
This re-downloads to `${WORK_DIR}/${TODAY}.tar.age` (the same path as the still-present local file from line 99-102) — but `rclone copy` does NOT overwrite by default unless the source is newer. With `--local-no-check-updated` (which is a *source-side* flag for the `local` backend, not relevant when the source is R2) the behavior is undefined. The subsequent `mv ${WORK_DIR}/${TODAY}.tar.age ${REDOWNLOAD_PATH}` would move the original (not the re-downloaded copy) into REDOWNLOAD_PATH. Then a fallback re-download to `redownloaded/` happens — but $REDOWNLOAD_PATH was already set to a different path. None of this is used downstream — lines 128-131 do the *real* re-download into `${WORK_DIR}/r2-redownload/`, and that is what is compared.

The two-block structure looks like a leftover from an earlier iteration. The first block (119-125) is dead code that does nothing useful AND has a real risk: if the `mv` succeeds, the original encrypted file is now at `${REDOWNLOAD_PATH}` and the subsequent age-of-the-original-by-name does not exist — but no downstream code references `${WORK_DIR}/${TODAY}.tar.age` after this point until line 208 (`stat -c%s "${WORK_DIR}/${TODAY}.tar.age"`), which would return 0 (fallback). The size in `last-success.json` would silently become `0`.

Worse: lines 217-218 stat the path that may have been moved away. Verify this in a smoke test before commit. Pre-existing code, not flipped in /opsx:apply, but it survived the round-3 review unflagged and the round-4 spec compliance commitment makes me re-flag it.

Fix: delete lines 119-126 entirely (the second block at 128-131 is the only correct path).

**Minor — `printf '%s\n' "$SAME_DAY_QUARANTINE" | wc -l` mis-counts empty/single-line.**
`backup-daily.sh:200-202`: when `$SAME_DAY_QUARANTINE` is empty, the `if [[ -n ... ]]` guard at line 199 already skips the branch, so `wc -l` would only run when there is ≥1 quarantine line. `printf '%s\n'` on a single non-empty line yields one newline → `wc -l` returns 1. OK, correct. Withdraw.

**Minor — Kuma callout placement.**
`backup-daily.sh:228-236` correctly drops the Kuma push branch. The comment cites design.md Decision 7 and lists the reinstate path. ✅

#### backup-preop.sh

Unchanged in /opsx:apply. Pre-existing inline `dispatch_alert` calls at lines 117 and 147 share the alert.sh subshell bug (B1) — both currently use `|| true` so they don't crash, but they produce misleading `ERROR All alert channels failed` log lines. No new findings beyond inheritance of B1.

#### verify-restore.sh

Unchanged in /opsx:apply. Inherits B1 at line 227. No new findings.

---

### n8n JSON review

#### MyFinanceBackup-DispatchAlert.json

JSON parses cleanly (`python -c "import json; json.load(...)"` returns OK).

**B2 (Blocker) — DispatchAlert uses `$env.*` for credentials that the spec mandates be stored as n8n credentials.**
`scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json:35,69,80`:
```jsonc
// ntfy node URL
"url": "=https://ntfy.sh/{{ $env.MYFINANCE_BACKUP_NTFY_TOPIC }}",

// Resend Authorization header
"value": "=Bearer {{ $env.MYFINANCE_BACKUP_RESEND_API_KEY }}",

// Resend body
"jsonBody": "={{ JSON.stringify({ from: $env.MYFINANCE_BACKUP_ALERT_FROM, to: $env.MYFINANCE_BACKUP_ALERT_TO, subject: $json.title, text: $json.body }) }}"
```
Multiple spec violations:
1. **Task §7.6 spec** says: *"ntfy HTTP Request POST `https://ntfy.sh/{{ $credentials.ntfyTopic }}` ... HTTP Request node POST `https://api.resend.com/emails` with header `Authorization: Bearer {{ $credentials.resendApiKey }}`"* — note `$credentials.*`, not `$env.*`.
2. **Task §8.4** says: *"map credentials in n8n's Credentials UI: ... `MYFINANCE_BACKUP_NTFY_TOPIC`, `MYFINANCE_BACKUP_RESEND_API_KEY`."* — credentials, not container env vars.
3. **Design.md line 225-226** says: *"Topic name is an unguessable slug stored as n8n credential `MYFINANCE_BACKUP_NTFY_TOPIC` ... Stored as n8n credential `MYFINANCE_BACKUP_RESEND_API_KEY` plus `MYFINANCE_BACKUP_ALERT_FROM` (sender) and `MYFINANCE_BACKUP_ALERT_TO` (recipient inbox)."*
4. **Runtime risk:** n8n's `N8N_BLOCK_ENV_ACCESS_IN_NODE` defaults to `true` on modern n8n versions (as documented in n8n's security hardening guide). With env access blocked, `$env.MYFINANCE_BACKUP_NTFY_TOPIC` returns undefined → URL becomes `https://ntfy.sh/undefined` → ntfy 200 to a public unrelated topic (or silently swallowed). `$env.MYFINANCE_BACKUP_RESEND_API_KEY` → empty Bearer → Resend returns 401. With both HTTP Request nodes having `continueOnFail: true`, the workflow appears green while sending zero alerts.
5. **Compose-side gap:** even if the operator unblocks env access, the v1 docker-compose for the n8n container does not document setting these env vars on the n8n service. The whole point of routing them through n8n credentials (per spec) is that they live in n8n's encrypted credential store, not in the n8n process env.

Fix:
- Replace each `$env.X` with `$credentials.Y` where Y is the credential field exposed by a "Generic Credential Type" or a dedicated HTTP Header Auth credential (operator decides), e.g. `$credentials.ntfyTopic`, `$credentials.resendApiKey`, `$credentials.alertFrom`, `$credentials.alertTo`.
- Add a `credentials` block to each HTTP Request node naming the credential. Without it, n8n cannot resolve `$credentials.*`.
- Update §8.4 import procedure to register four credentials, not just the four listed (ntfy, resend, runner-secret, age-identity) — there is no separate `alertFrom`/`alertTo` credential; consider bundling all three Resend fields into one custom credential type.

This is exploitable today: a successful operator install per the current docs will produce alerts that silently fail.

**M4 (Major) — Resend body uses `$json.title` / `$json.body` but the parent workflow passes them through Execute Workflow Trigger input definition; no defensive `JSON.stringify` of attacker-controllable content.**
The `jsonBody` uses `JSON.stringify({...})` which IS safe against newlines, backslashes, and quotes in `$json.title`/`$json.body`. ✅ That part is well-handled. Likewise the ntfy node uses `"contentType": "raw"` with the body in a separate field, so no injection risk there.

But: the ntfy Title header at line 41 passes `={{ $json.title }}` raw. ntfy.sh treats the `Title:` header as plain text — but if `$json.title` ever contains a CRLF, the request gets a header-injection. The Resend `Extract Error Info` code node in ErrorHandler.json constructs titles like `MyFinance backup ERROR — ${workflowName}` from n8n-controlled data, which is fine, but `dispatch_alert` callsites in the bash workers pass tail logs (e.g. `backup-daily.sh:186` body includes `${LOG_TAIL}` which may contain anything Postgres / rclone wrote to stderr). The bash worker's path goes through `runner.js → workers.js → bash`, **not** through the n8n DispatchAlert workflow — so this is not actually a path for the n8n node. Withdraw the CRLF concern *for the n8n DispatchAlert path*; but `alert.sh:41` ALSO passes title raw into `curl -H "Title: ${title}"` — if title ever contains a CRLF, header injection there too. Title is currently hardcoded by callers to short strings (`"MyFinance daily backup UPLOAD CORRUPTED"`, `"MyFinance daily backup VERIFY FAILED"`) so the risk is theoretical. Downgrade to **Minor** — defense-in-depth would strip CRLF from `$title` in `alert.sh` before building the header.

**Minor — Merge node is functionally inert.**
`MyFinanceBackup-DispatchAlert.json:86-97`: with both HTTP nodes setting `continueOnFail: true`, the workflow already cannot fail. The Merge node serializes the two parallel branches into one execution stream but its output is unused. Harmless. Could be removed for clarity, or kept as a placeholder for future "fan-in reporting" logic.

**Minor — `continueOnFail` is correctly set on both Resend and ntfy nodes.**
Lines 32 and 60 both have `"continueOnFail": true`. ✅ This is the contract from task §7.6 and design.md §10. If B2 is fixed, this gives the correct "one-failure-does-not-kill-the-other" parallel semantics.

#### Other n8n workflows

- `MyFinanceBackup-Daily.json` — unchanged in /opsx:apply, still uses `$credentials.runnerSecret` correctly. ✅
- `MyFinanceBackup-PreOp.json` — unchanged in /opsx:apply, still uses `$credentials.runnerSecret` correctly. ✅
- `MyFinanceBackup-ErrorHandler.json` — unchanged. Calls DispatchAlert via Execute Workflow correctly. Inherits B2 transitively (its alerts will silently fail until B2 is fixed).
- `MyFinanceBackup-Watchdog.json` — deleted from disk as per §7.4. ✅

All four remaining workflow files parse as valid JSON.

---

### Docs review (development-guide §12 + SPEC.md + data-model.md)

**§12.1 Daily cadence** — accurate to v1 cuts:
- Mentions ntfy + Resend in parallel for failure alerting (no Gmail, no Kuma). ✅
- Documents R2 lifecycle as ONE rule (`daily/` 30d), `weekly/monthly/pre-op/quarantine/` accumulate. ✅
- Mentions chained verify, server-side quarantine, status/last-success.json + status/last-verify.json. ✅

**§12.2 Backup before any Supabase write** — correct mandate-only language:
- Uses "should be preceded", "operator discipline", "documentation-only". ✅
- Explicitly says NOT "blocked"/"refuses"/"enforced". ✅ Matches the §11.2 B6-fix wording requirement.
- Lists covered operations (Flyway, MCP, psql, ad-hoc shell). ✅

**§12.3 Pre-op procedure**:
- n8n UI as only entry point, `^[A-Za-z0-9._+-]{3,60}$` regex correct. ✅
- HTTP 500 / `verify_failed` / `upload_corrupted` outcomes documented. ✅

**§12.4 Alerting (v1)**:
- ntfy + Resend as the two channels. ✅
- Documents BOTH failure modes uncovered: silent Schedule-Trigger non-fire AND whole-VPS outage. ✅
- Mitigation cited: operator's external uptime monitor on `n8n.datachefnow.com`. ✅
- "Kuma and healthchecks.io were considered and deferred" with reinstate-path. ✅
- **N1 (Minor)** — §12.4 paragraph 1 says "fired by `workers/alert.sh` on every failure path (daily worker non-zero exit, verify-failed, n8n ErrorHandler)". The "n8n ErrorHandler" branch does NOT go through `workers/alert.sh` — it goes through `MyFinanceBackup-DispatchAlert.json` (the n8n sub-workflow). Two independent code paths. Tighten the wording: "fired by `workers/alert.sh` on backup-worker inline failures (SHA mismatch, verify-failed), and by `MyFinanceBackup-DispatchAlert.json` on n8n-side failures (errorWorkflow, Schedule-Trigger 500 from runner). Same two destinations (ntfy + Resend) regardless of source."

**§12.5 Recovery procedure**:
- Paper identity drill steps clear. ✅
- Anchors: §2.5.4 of README exists (line 98). ✅
- Annual recurrence drill mentions Jan 15 calendar reminder (matches §10.6). ✅

**§12.6 Key rotation**:
- Five items rotated: age, R2, Resend, ntfy, runner secret. ✅
- v1 cuts list (Gmail App Password, Kuma push URL, healthchecks.io) clearly identified as no-longer-rotated. ✅

**SPEC.md cross-ref** (line 565-566):
- Anchors at `docs/development-guide.md#12-backup--disaster-recovery`. ✅
- Wording "debería" / "operational gate documentation-only" / "no hay enforcement automático" matches B6 fix. ✅

**docs/data-model.md cross-ref** (line 124):
- Anchors at the same section. ✅
- Wording "should" / "operator discipline" / "documentation-only gate" matches B6 fix. ✅

**No contradictions** between §12 and the design/proposal/tasks. ✅

---

### Task-completion honesty

**Falsely `[x]` (should be `[ ]` or partial):**

- **§4.3.14** — flipped `[x]` but only inline alerts at two callsites; early-step failures (pg_isready, pg_dump ×2, tar, age, rclone) bypass `dispatch_alert` and depend on n8n ErrorHandler. The script itself admits the gap at lines 248-253. See M2.
- **§4.2** — flipped `[x]` but the implementation has the subshell-scoping bug (B1) that breaks the "at least one channel succeeded" contract documented in the task spec. The function does not satisfy *"returns 0 if at least one channel succeeded"* — it returns 1 unconditionally.
- **§7.6** — flipped `[x]` but the implementation uses `$env.*` instead of `$credentials.*` as the task spec mandates. See B2.

**Honestly `[x]`:**

- §1.10, §2.5.1, §2.5.2, §2.5.7, §4.3.12, §7.3, §7.4, §7.7, §8.5, §9.9 — all DROPPED-marker checkboxes, faithfully reflecting v1 cuts.
- §2.4 — `.env.example` updated for v1 alerting; Gmail/Kuma/healthchecks placeholders all gone from the active config and reduced to deferred-cuts comments. ✅
- §11.1, §11.2, §11.3, §11.4 — development-guide §12 + SPEC.md §12 + data-model.md §3 cross-refs all landed. ✅
- §12.1 — `openspec validate --strict` re-run by reviewer: passes. ✅
- §12.2 — adversarial-review skill is being run (this is the 4th round). ✅

**Correctly left `[ ]`:**
- All §1 operator prereqs, §6.2/6.2b/6.5, §8.1-8.4a, §9.1-9.8, §10, §11.5, §12.3-12.5 — operator manual work. ✅

---

### Process-tooling check

**N2 (Minor) — progress.md staleness.**
`progress.md` line 18 says `last_updated: "2026-06-02T00:00:00Z"`, but the most recent branch commit is `2026-06-02T05:45Z` AND there are now uncommitted /opsx:apply changes on top. The body still has `last_completed: "7.7"`, `current_task: "1.1"`, and the "Notes for resuming" section does not reflect the §11.1-11.4 docs work or the §4.2/§4.3.12/§4.3.14/§7.6/§7.7 flips. Per project rule (CLAUDE.md + harness-progress-tracking Decision 2): rewrite before `/opsx:archive`. Mandatory.

Recommended fix: refresh `progress.md` to reflect `last_completed: "11.4"` (or the highest closed task in the current /opsx:apply session), update `next_step` to the §12.3-12.5 archive sequence + remaining operator work, append a decision-log entry for the round-4 adversarial review.

---

### New findings (Round 4)

| # | Severity | Area | Evidence | Recommended fix |
|---|---|---|---|---|
| B1 | Blocker | Workers / alert.sh | `scripts/backup/workers/alert.sh:79-81` — `&` puts each compound in a subshell so `ntfy_ok=1`/`resend_ok=1` never propagate to parent; function always returns 1 and always logs "All alert channels failed" | code: refactor to use `wait <pid>` on each backgrounded job and read exit status; tests: add smoke assertion that `dispatch_alert "T" "B" && echo ok` prints `ok` when at least one channel is mocked-200 |
| B2 | Blocker | n8n / DispatchAlert | `scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json:35,69,80` — uses `$env.MYFINANCE_BACKUP_NTFY_TOPIC` / `$env.MYFINANCE_BACKUP_RESEND_API_KEY` / `$env.MYFINANCE_BACKUP_ALERT_FROM` / `$env.MYFINANCE_BACKUP_ALERT_TO` instead of `$credentials.*` per task §7.6, §8.4, design.md §10 | code: swap to `$credentials.*` and add `credentials` block on each HTTP Request node; spec: clarify whether to bundle Resend fields into one custom credential type; tests: import to a fresh n8n on the VPS smoke and trigger §9.6 (forced-failure) — confirm both channels deliver |
| M2 | Major | Workers / backup-daily.sh | `scripts/backup/workers/backup-daily.sh:248-253` script comment admits the §4.3.14 "any failure → dispatch_alert" contract is not implemented; only SHA-mismatch (line 137) and verify-failed (line 185) paths alert; pg_isready, pg_dump×2, tar, age, rclone all bypass `dispatch_alert` | code: install `trap '...dispatch_alert "MyFinance backup FAILED step $LINENO" "$(tail -20 "$LOG_FILE")"...' ERR`; OR revise tasks.md §4.3.14 wording to acknowledge n8n-ErrorHandler-side coverage and reopen §4.3.14 to `[ ]` |
| M3 | Major | Workers / backup-daily.sh | `scripts/backup/workers/backup-daily.sh:119-126` dead-code re-download block (a) uses `--local-no-check-updated` flag inappropriately for an R2 source, (b) `mv` may quietly move the ORIGINAL encrypted file out of `${WORK_DIR}/${TODAY}.tar.age`, leaving line 208's `stat -c%s "${WORK_DIR}/${TODAY}.tar.age"` to return 0 → `size: 0` in `last-success.json` silently; the *real* re-download path is at lines 128-131 | code: delete lines 119-126; verify smoke test still passes |
| M4 | Major | Tasks honesty | tasks.md flipped `[x] 4.2`, `[x] 4.3.14`, `[x] 7.6` while the underlying implementations have B1 / M2 / B2 defects respectively — false-positive completion claims that the archive workflow would treat as merge-ready | revise tasks.md to either `[ ]` or `[x] (partial — see B1/B2/M2 in round-4 review)` until the code is correct |
| N1 | Minor | docs / development-guide §12.4 | `docs/development-guide.md` §12.4 attributes the n8n ErrorHandler path to `workers/alert.sh`; they are two independent code paths sharing only the same destinations | docs: split the alerting attribution into "worker-inline" vs "n8n-side" |
| N2 | Minor | process-tooling / progress.md | `openspec/changes/supabase-backup-policy/progress.md:9-18` — `last_updated` stale (older than most recent commit AND than the uncommitted /opsx:apply work), `last_completed: "7.7"` does not reflect the §11.1-11.4 + §4.x + §7.6+7.7 flips | rewrite progress.md to reflect the latest task state before archive |
| m1 | Minor | Workers / alert.sh | `scripts/backup/workers/alert.sh:34,40` — `$MYFINANCE_BACKUP_NTFY_TOPIC` interpolated into curl URL without a sanity regex; mismatched values would silently POST to a different topic | code: add `if [[ ! "$topic" =~ ^[A-Za-z0-9_-]{16,}$ ]]; then return 1; fi` |
| m2 | Minor | Workers / alert.sh | `scripts/backup/workers/alert.sh:41` — `curl -H "Title: ${title}"` would CRLF-inject if title ever contained a newline; current callers use hardcoded strings only, low likelihood | code: `title="${title//$'\r'/}"; title="${title//$'\n'/ / }"` before building header |

---

### Acceptance criteria coverage

- ✅ Workers/scripts no longer carry Gmail SMTP code path (alert.sh send_resend uses HTTP API; backup-daily.sh §4.3.12 is a DROPPED comment).
- ✅ Watchdog workflow JSON deleted from disk.
- ✅ DispatchAlert workflow JSON validates as JSON; both HTTP nodes have `continueOnFail: true`; parallel fan-out structure intact.
- ❌ DispatchAlert workflow uses `$env.*` instead of `$credentials.*` per spec — implementation will silently fail to send any alerts on a default-hardened n8n (B2).
- ❌ alert.sh "at-least-one-succeeds" semantics broken by subshell scoping (B1).
- ❌ §4.3.14 worker-side "any failure → dispatch_alert" contract not implemented (M2).
- ✅ docs/development-guide.md §12 added with daily / write-gate / pre-op / alerting / recovery / key rotation subsections; v1 cuts accurately reflected.
- ✅ SPEC.md §12 + docs/data-model.md §3 cross-refs point at the right anchor.
- ✅ Tasks honestly mark operator-only items as `[ ]`.
- ❌ Three tasks marked `[x]` without the underlying work being correct (§4.2, §4.3.14, §7.6) — M4.

---

### Scope assessment

- **No scope creep** in the /opsx:apply patch — every touched file is justified by §4.2 / §4.3.12 / §7.6 / §7.7 / §11.x.
- **No spec drift** in dev-guide §12; matches the v1 cuts in proposal.md / design.md.
- **Implementation gap, not scope gap:** B1, B2, M2 are bugs against the *implementer's own task spec*, not against new requirements outside scope.

---

### What I tried to break but couldn't

- **DispatchAlert workflow JSON malformedness.** Parses cleanly; merge-node typing acceptable; expression syntax valid (the credential-vs-env issue is semantic, not syntactic).
- **Cross-doc references broken.** §12.5 step 6 points at `scripts/backup/README.md §2.5.4 Operator runbook`; that anchor exists at README line 98. SPEC.md §12 link to dev-guide-§12-backup--disaster-recovery resolves. data-model.md §3 link resolves. ✅
- **JSON-body injection via title/body in Resend node.** `JSON.stringify(...)` defends correctly against newlines, backslashes, quotes. ✅
- **JSON-body injection via title/body in `alert.sh` Resend call.** `jq -nc --arg ... '{from: $from, to: $to, subject: $subject, text: $text}'` correctly escapes any character. ✅ (verified by inspection of the jq invocation at alert.sh:63-68)
- **Identity-leak via /proc/<pid>/environ from the child bash workers.** `workers.js:53-56` deletes `MYFINANCE_BACKUP_AGE_IDENTITY` from child env; identity is delivered via stdin and the worker writes it to a tmpfs file with `umask 0177` (verify-restore.sh:81). ✅

---

### Verdict justification

**2 Blockers + 3 Majors → FAIL.**

The /opsx:apply patch correctly removed Gmail SMTP from the code paths and dropped the Kuma success-push comment. The docs work for §11.1-§11.4 is solid and faithfully encodes the v1 cuts. The Watchdog JSON deletion is clean.

But the patch introduced (or shipped without catching) two ship-stopping bugs:

1. **`alert.sh` always returns 1 and always logs "All alert channels failed"** because the subshell-scoping bug in the parallel-fan-out makes the success counters never propagate. (B1)
2. **`MyFinanceBackup-DispatchAlert.json` uses `$env.*` instead of `$credentials.*`** for ntfy topic, Resend API key, Resend From/To. On a default-hardened modern n8n (env access blocked in expressions), these resolve to undefined and the workflow silently sends zero alerts while reporting green. (B2)

Combined, the two bugs mean the **failure-alerting pipeline is broken end-to-end** on /opsx:apply day-1. The whole v1-cuts thesis ("ntfy + Resend is enough; we don't need Kuma / healthchecks.io") rests on those two channels actually delivering — and they don't.

Resolve B1 + B2 before `/opsx:archive`. M2 / M3 / M4 should also be addressed (M3 risks `size: 0` in `last-success.json`; M2 narrows the failure-alert surface; M4 unfreezes task-completion honesty). N1 / N2 / m1 / m2 are cleanups.

After resolution, re-run smoke test §9.6 (forced-failure with bad DB password) and confirm ntfy + Resend BOTH deliver to operator phone + inbox before archiving.
