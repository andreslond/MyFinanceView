'use strict';

const { timingSafeEqual } = require('crypto');

/**
 * Express middleware that validates the X-Runner-Secret header using a
 * timing-safe comparison. Prevents timing-oracle attacks where an attacker
 * probes character by character by measuring response latency.
 *
 * Rejects with 401 and an EMPTY body on mismatch — the empty body is
 * intentional: leaking even "unauthorized" in the body wastes bytes and
 * slightly aids enumeration.
 *
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 * @param {import('express').NextFunction} next
 */
function requireRunnerSecret(req, res, next) {
  const expected = process.env.MYFINANCE_BACKUP_RUNNER_SECRET;
  if (!expected) {
    // Misconfigured: no secret set. Fail closed — never allow unauthenticated access.
    res.status(503).end();
    return;
  }

  const provided = req.headers['x-runner-secret'] || '';

  // timingSafeEqual requires same-length buffers; pad both to the same length
  // using the expected value's length so the comparison is always constant-time
  // relative to the expected secret's length.
  const expectedBuf = Buffer.from(expected, 'utf8');
  const providedBuf = Buffer.alloc(expectedBuf.length, 0);
  Buffer.from(provided, 'utf8').copy(providedBuf);

  if (!timingSafeEqual(expectedBuf, providedBuf) || provided.length !== expected.length) {
    res.status(401).end();
    return;
  }

  next();
}

module.exports = { requireRunnerSecret };
