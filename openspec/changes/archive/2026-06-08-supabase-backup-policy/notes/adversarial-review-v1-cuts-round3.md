## Adversarial Review · supabase-backup-policy · Round 3 (pass-3 edits) · 2026-06-02

**Verdict:** PASS WITH GAPS

**Scope reviewed:**
- Pass-3 working-tree edits (not yet committed) on `feat/supabase-backup-policy-replant`
- 3 modified files: `scripts/backup/README.md` (heavy), `scripts/backup/test-smoke.sh` (~6 lines), `openspec/changes/supabase-backup-policy/tasks.md` (4 task lines)
- `git diff --ignore-cr-at-eol HEAD` against commit `97355e7`
- Targeted re-verification of Round-2 findings N2 + N3 + N1-tracking

---

### Round-2 N2 + N3 verification

**N2 (Major) — `scripts/backup/README.md` Gmail+Kuma drift — FIXED.**
- §2.5.1 Overview (README:3-22) rewritten. Line 13 says "Alerts via **ntfy.sh push + Resend transactional email** (`alerts@datachefnow.com`...)". Line 14 makes the v1 "no in-cluster dead-man-switch" stance explicit. Line 22 lists the alerting cut "from 3 channels (ntfy + Gmail SMTP + Kuma) to 2 (ntfy + Resend HTTP API)" as a historical callout, not as current behaviour. ✅
- §2.5.2 Architecture (README:26-66): ASCII diagram (lines 33-47) ends at "status/last-success.json + last-verify.json → R2" — the previous "Kuma push ping" terminal node is gone. Line 49 explicitly says "no Kuma success push at the bottom of the success path" as a historical v1 callout. ✅
- §2.5.5 Key rotation (README:185-202): table now has rows for **Resend API key** (line 193) and **ntfy topic** (line 194). The "v1 cuts" block (lines 197-202) explicitly lists the dropped secrets (Gmail App Password, Kuma push URL, healthchecks URL) AND tells the reader how to reinstate them. ✅

**N3 (Minor) — `test-smoke.sh` dead Gmail/Kuma exports — FIXED.**
- `test-smoke.sh:134-141` removes `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` and `MYFINANCE_BACKUP_KUMA_PUSH_URL`, adds Resend trio (`MYFINANCE_BACKUP_RESEND_API_KEY`, `MYFINANCE_BACKUP_ALERT_FROM`, `MYFINANCE_BACKUP_ALERT_TO`), all empty placeholders. Inline comment (lines 135-138) documents the v1 rationale and the "smoke leaves them empty so the failure-alert dispatcher fires but does not actually hit external services" intent. ✅
- Grep over the whole file for `gmail|kuma|smtp|app_password` returns only the historical comment at line 135 — no other residue. ✅

---

### N1 tracking check

Confirmed the author did NOT silently mark these done:
- `tasks.md:48` §4.2 (`alert.sh` Resend rewrite) — `[ ]` ✅
- `tasks.md:63` §4.3.12 (`backup-daily.sh` Kuma branch removal) — `[ ]` ✅
- `tasks.md:167` §7.6 (`MyFinanceBackup-DispatchAlert.json` Resend rewrite) — `[ ]` ✅
- `git diff HEAD -- scripts/backup/workers/alert.sh scripts/backup/workers/backup-daily.sh scripts/backup/n8n/MyFinanceBackup-DispatchAlert.json` is empty — files untouched from Round-2 state. ✅
- `alert.sh:43-64` still reads `MYFINANCE_BACKUP_GMAIL_APP_PASSWORD` and POSTs to `smtp://smtp.gmail.com:587` (i.e. the v3 implementation), matching the documented "deferred to /opsx:apply" state.

---

### New findings (introduced or surfaced by the pass-3 edits)

| # | Severity | Area | Evidence | Recommended fix |
|---|---|---|---|---|
| R3-1 | Minor | Spec drift — operator runbook | `scripts/backup/README.md:241` says "This upload resets the Watchdog's `drill OVERDUE` alert (task 7.4)." But `tasks.md:165` flags §7.4 as **DROPPED (v1 cut 2026-06-01) — Watchdog workflow removed**, and `tasks.md:207` §10.6 explicitly states "the watchdog OVERDUE alert is removed (§7.4 dropped); the calendar reminder is now the only cadence cue". The README §2.5.7 step thus instructs the operator to "reset" an alert that no longer exists. §2.5.7 is currently marked `[x]` in `tasks.md:31` but its content is stale. | docs: in README §2.5.7 (line 241), replace "This upload resets the Watchdog's `drill OVERDUE` alert (task 7.4)" with "This upload records the drill on R2 for future audit (v1: no Watchdog alert — see `tasks.md` §7.4 dropped; cadence enforcement is the annual calendar reminder per §10.6)". Also flip `tasks.md:31` §2.5.7 from `[x]` to `[ ]` with a "v1 cut" callout matching the §2.5.1/§2.5.2/§2.5.4 pattern. |
| R3-2 | Minor | Spec drift — operator runbook | `scripts/backup/README.md` §2.5.4 (lines 98-181) is the "Operator runbook". The Round-2 N2 callout in `tasks.md:24` for §2.5.4 explicitly requires "revise to point at the v1 prereqs checklist (no Gmail App Password, no Kuma Push Monitor, Resend API key instead)". A grep for `prereqs\|prereq\|checklist\|operator-prereqs` in README.md returns ONLY the §2.5.7 heading — §2.5.4 has no link to `operator-prereqs-checklist.md` or to the v1 prereqs work. The §2.5.4 install/runbook block also still says "Manual pre-op backup (v3 — n8n UI, no webhook)" with no "v1" framing. Tracked correctly as `[ ]` at `tasks.md:23`, so this is **not** a regression — it's the same N2-residual gap, just explicit. Logging as Minor so it doesn't get lost when §2.5.4 is finally rewritten. | docs: when /opsx:apply tackles `tasks.md` §2.5.4, the rewrite MUST (a) add a link to `openspec/changes/supabase-backup-policy/operator-prereqs-checklist.md`, (b) reframe the "(v3 — n8n UI, no webhook)" sub-heading as "(v1 — manual trigger from n8n UI)", and (c) remove the Gmail App Password reference from any future install steps. |
| R3-3 | Question | Process-tooling | `test-smoke.sh:135-138` claims "The runner / workers gracefully no-op these channels if the env vars are empty". Today this is **half-true**: `alert.sh:28-32` does early-return on empty `MYFINANCE_BACKUP_NTFY_TOPIC`, but the Resend branch does not exist yet (alert.sh is still v3 Gmail). Smoke-test happy path still passes (alert.sh isn't reached when backup-daily.sh succeeds), but if a future operator runs `bash scripts/backup/workers/alert.sh "title" "body"` directly with the new env layout, it will silently fall back to the Gmail-SMTP path and fail with "MYFINANCE_BACKUP_GMAIL_APP_PASSWORD not set". Is the implicit contract "smoke test acceptance is only the happy path until §4.2 lands" the intended one, or should §4.2 land before test-smoke.sh ships with these env-var placeholders? | spec/process: confirm in `progress.md > resuming` that test-smoke.sh's new Resend env-var exports are intentionally "ahead of the implementation" of §4.2 — i.e. they describe the post-§4.2 contract, and the dispatcher path is not exercised by the current smoke run. No code change needed unless the contract is the other way around. |

---

### What I tried to break but couldn't

- **Silent re-introduction of Kuma/Gmail into the README via a missed section.** Full `grep -niE 'gmail|kuma|watchdog|smtp|app[_ -]?password'` over `scripts/backup/README.md` returns 6 hits — every one is either explicitly framed as a "v1 cut" historical callout (lines 14, 22, 49, 198, 199) or the stale §2.5.7 reference at line 241 (logged as R3-1). No accidental "Alerts go to Gmail" sentence survived. ✅
- **Silent worker rewrite.** `git diff HEAD -- scripts/backup/workers/ scripts/backup/n8n/` is empty. alert.sh:1-87 still self-identifies as "Gmail SMTP" in the file header comment. ✅
- **Hidden tasks.md flip of §4.2 / §4.3.12 / §7.6.** All three remain `[ ]`. ✅
- **Test-smoke.sh now exercising a code path that doesn't exist.** Verified that the smoke runner (test-smoke.sh:155-202) only validates the happy path of `backup-daily.sh`; it never sources `alert.sh` or exercises the alert dispatcher, so the env-var/alert.sh mismatch is dormant. ✅
- **README §2.5.5 missing the Resend or ntfy row.** Both present at README:193 + README:194. ✅
- **README §2.5.6 disaster-scenarios table accidentally referencing Kuma or Gmail.** Lines 206-220: no mention of either. The "VPS gone" / "R2 gone" paths are alerting-channel-agnostic. ✅

---

### Acceptance criteria coverage (Round-2 follow-up only)

- ✅ N2: README content drift from v3 (Gmail+Kuma) → v1 (ntfy+Resend) is now consistent in §2.5.1, §2.5.2, §2.5.5.
- ✅ N3: test-smoke.sh dead Gmail/Kuma exports replaced with Resend placeholders + rationale comment.
- ❌ §2.5.7 step (README:241) still references the dropped Watchdog `drill OVERDUE` alert — see R3-1.
- ⏳ §2.5.4 still pending v1 rewrite to point at the operator prereqs checklist — see R3-2 (correctly tracked `[ ]`).

---

### Scope assessment

- **Scope creep:** none. The pass-3 edits are tightly scoped to N2 + N3.
- **Missing:** §2.5.7 should have been flipped to `[ ]` together with §2.5.1/§2.5.2/§2.5.4 because its README content is stale in the exact same way (R3-1).

---

### Verdict justification

0 Blockers · 0 Majors · 2 Minors · 1 Question.

Round-2 N2 + N3 are both functionally fixed. The remaining `[ ]` items (N1, R3-1, R3-2) are correctly tracked. R3-1 is the only Round-3 finding that is *not* tracked anywhere in tasks.md yet — it should be either fixed inline or added as a sub-bullet under §2.5.7 before `/opsx:archive`. R3-2 and R3-3 are observations to surface in `progress.md` for continuity.

No blocking issues. Safe to commit pass-3 edits; safe to continue with /opsx:apply on N1 + §1 prereqs in tomorrow's session.
