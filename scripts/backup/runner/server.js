'use strict';

const express = require('express');
const helmet = require('helmet');
const pino = require('pino');
const https = require('https');

const { requireRunnerSecret } = require('./auth');
const { acquireRunLock, releaseRunLock } = require('./mutex');
const { runWorker } = require('./workers');

// ---------------------------------------------------------------------------
// Logger setup — pino with redaction of secrets in headers and env vars
// ---------------------------------------------------------------------------
const logger = pino({
  level: process.env.LOG_LEVEL || 'info',
  redact: {
    paths: [
      'req.headers["x-runner-secret"]',
      'req.headers["x-webhook-secret"]',
    ],
    censor: '[REDACTED]',
  },
});

const VERSION = require('./package.json').version;

// ---------------------------------------------------------------------------
// R2 status cache (60 s TTL)
// ---------------------------------------------------------------------------
const STATUS_CACHE_TTL_MS = 60_000;
let statusCache = null;
let statusCacheAt = 0;

const R2_ACCOUNT_ID = process.env.BACKUP_R2_ACCOUNT_ID;
const R2_BUCKET = process.env.BACKUP_R2_BUCKET || 'my-finance-view-backups';
const R2_ACCESS_KEY = process.env.BACKUP_R2_ACCESS_KEY_ID;
const R2_SECRET_KEY = process.env.BACKUP_R2_SECRET_ACCESS_KEY;

/**
 * Fetch a JSON file from R2 via the S3-compatible HTTPS endpoint.
 * Returns null if the object is missing (404). Throws on other errors.
 *
 * This is intentionally a minimal HTTP client rather than the AWS SDK to
 * keep the runner dependency footprint small. R2 supports unsigned GET for
 * public buckets but the bucket is private, so we use pre-signed style auth
 * via the Authorization header (AWS Signature V4). For simplicity we use
 * rclone as the fetch mechanism via a subprocess.
 *
 * @param {string} key - object key (e.g. "status/last-success.json")
 * @returns {Promise<object|null>}
 */
async function fetchR2Json(key) {
  const { execFile } = require('child_process');
  return new Promise((resolve, reject) => {
    execFile('rclone', ['cat', `r2:${R2_BUCKET}/${key}`], { encoding: 'utf8' }, (err, stdout) => {
      if (err) {
        // rclone exits non-zero when the object does not exist
        if (err.stderr && err.stderr.includes('Object not found')) {
          resolve(null);
          return;
        }
        // Any other error means R2 is unreachable or misconfigured
        reject(new Error(`R2 unreachable: ${err.message}`));
        return;
      }
      try {
        resolve(JSON.parse(stdout));
      } catch {
        resolve(null);
      }
    });
  });
}

/**
 * Read status from R2 with a 60 s in-process cache.
 * Returns { lastSuccess, lastPreop, lastVerify, lastDrill } — any field may be null.
 * Throws an Error with code 'R2_UNREACHABLE' if R2 is not accessible.
 */
async function readStatus() {
  const now = Date.now();
  if (statusCache && now - statusCacheAt < STATUS_CACHE_TTL_MS) {
    return statusCache;
  }

  const [lastSuccess, lastPreop, lastVerify, lastDrill] = await Promise.all([
    fetchR2Json('status/last-success.json'),
    fetchR2Json('status/last-preop.json'),
    fetchR2Json('status/last-verify.json'),
    fetchR2Json('status/last-drill.json'),
  ]);

  statusCache = { lastSuccess, lastPreop, lastVerify, lastDrill };
  statusCacheAt = now;
  return statusCache;
}

// ---------------------------------------------------------------------------
// Express app
// ---------------------------------------------------------------------------
const app = express();
app.use(helmet());
app.use(express.json({ limit: '16kb' }));

// GET /healthz — no auth, returns runner version for Docker/n8n liveness checks
app.get('/healthz', (_req, res) => {
  res.json({ status: 'ok', version: VERSION });
});

// GET /status — no auth, reads from R2 (canonical source of truth)
// Cache TTL: 60 s. Returns 503 if R2 is unreachable.
app.get('/status', async (_req, res) => {
  try {
    const status = await readStatus();
    res.json(status);
  } catch (err) {
    logger.error({ err }, 'R2 unreachable when serving /status');
    // Invalidate stale cache so next caller retries
    statusCache = null;
    res.status(503).json({ error: 'r2_unreachable' });
  }
});

// POST /run/daily — authenticated, mutex-gated
app.post('/run/daily', requireRunnerSecret, async (req, res) => {
  if (!acquireRunLock()) {
    return res.status(409).json({ error: 'run_in_progress' });
  }
  logger.info('Starting daily backup run');
  try {
    const identity = process.env.MYFINANCE_BACKUP_AGE_IDENTITY;
    const result = await runWorker('daily', { identity });
    // Invalidate status cache so /status reflects the new success immediately
    statusCache = null;
    res.json(result);
  } catch (err) {
    logger.error({ err }, 'Daily backup worker failed');
    res.status(500).json({ error: 'worker_failed', message: err.message });
  } finally {
    releaseRunLock();
  }
});

// POST /run/preop — authenticated, mutex-gated
app.post('/run/preop', requireRunnerSecret, async (req, res) => {
  if (!acquireRunLock()) {
    return res.status(409).json({ error: 'run_in_progress' });
  }
  const { reason } = req.body || {};
  logger.info({ reason }, 'Starting pre-op backup run');
  try {
    const identity = process.env.MYFINANCE_BACKUP_AGE_IDENTITY;
    const result = await runWorker('preop', {
      identity,
      env: { REASON: reason || '' },
    });
    statusCache = null;
    res.json(result);
  } catch (err) {
    logger.error({ err }, 'Pre-op backup worker failed');
    res.status(500).json({ error: 'worker_failed', message: err.message });
  } finally {
    releaseRunLock();
  }
});

// POST /run/verify — authenticated, mutex-gated (ad-hoc operator invocation)
app.post('/run/verify', requireRunnerSecret, async (req, res) => {
  if (!acquireRunLock()) {
    return res.status(409).json({ error: 'run_in_progress' });
  }
  const { target } = req.body || {};
  logger.info({ target }, 'Starting ad-hoc verify-restore run');
  try {
    const identity = process.env.MYFINANCE_BACKUP_AGE_IDENTITY;
    const result = await runWorker('verify', {
      identity,
      env: target ? { VERIFY_TARGET: target } : {},
    });
    statusCache = null;
    res.json(result);
  } catch (err) {
    logger.error({ err }, 'Verify-restore worker failed');
    res.status(500).json({ error: 'worker_failed', message: err.message });
  } finally {
    releaseRunLock();
  }
});

// ---------------------------------------------------------------------------
// Start
// ---------------------------------------------------------------------------
const PORT = parseInt(process.env.PORT || '8080', 10);
app.listen(PORT, '0.0.0.0', () => {
  logger.info({ port: PORT, version: VERSION }, 'myfinance-backup-runner started');
});

module.exports = app; // exported for tests
