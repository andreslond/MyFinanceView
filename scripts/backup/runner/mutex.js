'use strict';

/**
 * In-process run-lock mutex for the HTTP entrypoints.
 *
 * DESIGN ASYMMETRY (important — do not remove this comment):
 * The mutex governs HTTP-triggered runs only. When backup-daily.sh invokes
 * verify-restore.sh as an in-process subprocess (the chained verify path,
 * task 4.3.9), that subprocess call bypasses this mutex entirely — it is
 * spawned from within the already-locked daily run, so no additional lock
 * acquisition is needed. A concurrent external POST /run/verify while the
 * chained verify is running WILL return 409 (because the daily lock is held),
 * which is correct: two simultaneous verify runs on the same ephemeral
 * container would conflict.
 *
 * Usage:
 *   const { acquireRunLock, releaseRunLock } = require('./mutex');
 *   if (!acquireRunLock()) { res.status(409).json({ error: 'run_in_progress' }); return; }
 *   try { ... } finally { releaseRunLock(); }
 */

let _locked = false;

/**
 * Attempt to acquire the run lock.
 * @returns {boolean} true if the lock was acquired, false if already held.
 */
function acquireRunLock() {
  if (_locked) return false;
  _locked = true;
  return true;
}

/**
 * Release the run lock. Safe to call even if not held (idempotent).
 */
function releaseRunLock() {
  _locked = false;
}

/**
 * Inspect current lock state without modifying it.
 * Useful for tests and health checks.
 * @returns {boolean}
 */
function isLocked() {
  return _locked;
}

module.exports = { acquireRunLock, releaseRunLock, isLocked };
