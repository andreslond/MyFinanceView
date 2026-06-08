'use strict';

const { requireRunnerSecret } = require('./auth');

function makeReqResMock(headerValue) {
  const req = { headers: { 'x-runner-secret': headerValue } };
  const res = {
    _status: null,
    _ended: false,
    status(code) { this._status = code; return this; },
    end() { this._ended = true; return this; },
    json(body) { this._body = body; return this; },
  };
  return { req, res };
}

describe('auth middleware', () => {
  const ORIGINAL_ENV = process.env.MYFINANCE_BACKUP_RUNNER_SECRET;

  beforeEach(() => {
    process.env.MYFINANCE_BACKUP_RUNNER_SECRET = 'correct-secret-32-chars-xxxxxxxxx';
  });

  afterEach(() => {
    if (ORIGINAL_ENV === undefined) {
      delete process.env.MYFINANCE_BACKUP_RUNNER_SECRET;
    } else {
      process.env.MYFINANCE_BACKUP_RUNNER_SECRET = ORIGINAL_ENV;
    }
  });

  test('shouldCallNextWhenSecretIsCorrect', () => {
    const { req, res } = makeReqResMock('correct-secret-32-chars-xxxxxxxxx');
    const next = jest.fn();
    requireRunnerSecret(req, res, next);
    expect(next).toHaveBeenCalledTimes(1);
    expect(res._status).toBeNull();
  });

  test('shouldReturn401WhenSecretIsWrong', () => {
    const { req, res } = makeReqResMock('wrong-secret');
    const next = jest.fn();
    requireRunnerSecret(req, res, next);
    expect(next).not.toHaveBeenCalled();
    expect(res._status).toBe(401);
    expect(res._ended).toBe(true);
  });

  test('shouldReturn401WhenSecretIsMissing', () => {
    const req = { headers: {} };
    const res = {
      _status: null,
      _ended: false,
      status(code) { this._status = code; return this; },
      end() { this._ended = true; return this; },
    };
    const next = jest.fn();
    requireRunnerSecret(req, res, next);
    expect(next).not.toHaveBeenCalled();
    expect(res._status).toBe(401);
  });

  test('shouldReturn401WhenSecretHasCorrectPrefixButDifferentLength', () => {
    // An attacker sending a truncated prefix of the real secret should be rejected
    const { req, res } = makeReqResMock('correct-secret-32-chars-xxxxxxxx'); // one char shorter
    const next = jest.fn();
    requireRunnerSecret(req, res, next);
    expect(next).not.toHaveBeenCalled();
    expect(res._status).toBe(401);
  });

  test('shouldReturn503WhenSecretEnvVarIsNotConfigured', () => {
    delete process.env.MYFINANCE_BACKUP_RUNNER_SECRET;
    const { req, res } = makeReqResMock('anything');
    res.status = jest.fn().mockReturnValue(res);
    res.end = jest.fn().mockReturnValue(res);
    const next = jest.fn();
    requireRunnerSecret(req, res, next);
    expect(next).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(503);
  });
});
