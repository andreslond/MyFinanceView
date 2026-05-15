'use strict';

const { spawn } = require('child_process');
const { randomUUID } = require('crypto');
const path = require('path');
const fs = require('fs');

const WORKERS_DIR = '/opt/myfinance-backup/workers';
const LOGS_DIR = '/var/lib/myfinance-backup/logs';

/**
 * Spawn a bash worker script and return a promise that resolves with the
 * parsed JSON payload from the worker's final stdout line.
 *
 * Identity handling (B7 fix):
 *   (a) The worker's environment has MYFINANCE_BACKUP_AGE_IDENTITY scrubbed
 *       so the secret never lives in a child process environment (visible via
 *       /proc/<pid>/environ on Linux).
 *   (b) The identity bytes are written to the child's stdin pipe so the worker
 *       can read them via `IFS= read -rd '' AGE_IDENTITY < /dev/stdin`.
 *   (c) The WORKER is responsible for persisting the identity to a tmpfs file
 *       and installing the EXIT trap that wipes it — this keeps the file's
 *       lifetime tied to the worker's exit, not to a separate Node-managed
 *       lifecycle that could race the worker on failure paths.
 *
 * @param {'daily'|'preop'|'verify'} name - worker name (maps to <name>.sh)
 * @param {object} [options]
 * @param {object} [options.env] - additional env vars merged into the child env
 * @param {string} [options.identity] - age private key content to pipe into stdin
 * @returns {Promise<object>} parsed JSON from the worker's final stdout line
 */
async function runWorker(name, options = {}) {
  const scriptName = name === 'daily' ? 'backup-daily.sh'
    : name === 'preop' ? 'backup-preop.sh'
    : name === 'verify' ? 'verify-restore.sh'
    : null;

  if (!scriptName) {
    throw new Error(`Unknown worker: ${name}`);
  }

  const scriptPath = path.join(WORKERS_DIR, scriptName);
  const runId = randomUUID();
  const logPath = path.join(LOGS_DIR, `${runId}.log`);

  // Ensure logs directory exists
  fs.mkdirSync(LOGS_DIR, { recursive: true });

  // (a) Scrub the age identity from the child environment
  const childEnv = {
    ...process.env,
    ...options.env,
    MYFINANCE_BACKUP_AGE_IDENTITY: undefined,
  };
  // Remove the undefined key entirely so it does not appear as "undefined" string
  delete childEnv.MYFINANCE_BACKUP_AGE_IDENTITY;

  return new Promise((resolve, reject) => {
    const logStream = fs.createWriteStream(logPath, { flags: 'a' });
    const child = spawn('/bin/bash', [scriptPath], {
      env: childEnv,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let lastLine = '';
    let stdoutBuf = '';

    child.stdout.on('data', (chunk) => {
      logStream.write(chunk);
      stdoutBuf += chunk.toString('utf8');
      const lines = stdoutBuf.split('\n');
      stdoutBuf = lines.pop(); // keep incomplete last line in buffer
      for (const line of lines) {
        if (line.trim()) lastLine = line.trim();
      }
    });

    child.stderr.on('data', (chunk) => {
      logStream.write(chunk);
    });

    // (b) Write identity to stdin pipe, then close stdin so the worker's
    //     `read -rd ''` sees EOF and returns.
    if (options.identity) {
      child.stdin.write(options.identity, 'utf8');
    }
    child.stdin.end();

    child.on('close', (code) => {
      logStream.end();
      if (code !== 0) {
        reject(new Error(`Worker ${name} exited with code ${code}. Log: ${logPath}`));
        return;
      }
      // Parse the final JSON line emitted by emit_json_result in _common.sh
      if (!lastLine) {
        reject(new Error(`Worker ${name} produced no JSON output. Log: ${logPath}`));
        return;
      }
      try {
        resolve(JSON.parse(lastLine));
      } catch (err) {
        reject(new Error(`Worker ${name} final line was not valid JSON: "${lastLine}". Log: ${logPath}`));
      }
    });

    child.on('error', (err) => {
      logStream.end();
      reject(err);
    });
  });
}

module.exports = { runWorker };
