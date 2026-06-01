'use strict';

const { acquireRunLock, releaseRunLock, isLocked } = require('./mutex');

describe('mutex', () => {
  // Reset lock state before each test to avoid inter-test pollution
  beforeEach(() => {
    releaseRunLock();
  });

  test('shouldAcquireLockWhenNotHeld', () => {
    expect(acquireRunLock()).toBe(true);
    expect(isLocked()).toBe(true);
    releaseRunLock();
  });

  test('shouldReturnFalseWhenLockAlreadyHeld', () => {
    acquireRunLock();
    // Second acquire while locked must return false (concurrent request scenario)
    expect(acquireRunLock()).toBe(false);
    releaseRunLock();
  });

  test('shouldReleaseLockSoNextAcquireSucceeds', () => {
    acquireRunLock();
    releaseRunLock();
    // After release, a new sequential caller can acquire
    expect(acquireRunLock()).toBe(true);
    expect(isLocked()).toBe(true);
    releaseRunLock();
  });

  test('shouldBeIdempotentOnRelease', () => {
    // Calling releaseRunLock when not held must not throw
    releaseRunLock();
    releaseRunLock();
    expect(isLocked()).toBe(false);
  });

  test('shouldAllowReacquireAfterRelease', () => {
    // Simulates sequential HTTP runs: run A, release, then run B acquires
    expect(acquireRunLock()).toBe(true);
    releaseRunLock();
    expect(acquireRunLock()).toBe(true);
    releaseRunLock();
    expect(isLocked()).toBe(false);
  });

  test('shouldBlockConcurrentAcquireWhileLockHeld', () => {
    // Simulates two concurrent HTTP requests arriving simultaneously.
    // The mutex is synchronous (not async), so this is deterministic.
    const firstAcquired = acquireRunLock();   // first request: succeeds
    const secondAcquired = acquireRunLock();  // second request: blocked
    expect(firstAcquired).toBe(true);
    expect(secondAcquired).toBe(false);
    releaseRunLock();
  });
});
