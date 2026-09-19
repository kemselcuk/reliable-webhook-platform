import test from 'node:test';
import assert from 'node:assert/strict';
import {
  deliveryStatusLabel,
  isReplayableStatus,
  pluralizeAttempts,
} from '../src/delivery-formatters.js';

test('formats bounded delivery statuses and keeps replay eligibility terminal-only', () => {
  assert.equal(deliveryStatusLabel('RETRY_SCHEDULED'), 'Retry scheduled');
  assert.equal(deliveryStatusLabel('SUCCESS'), 'Delivered');
  assert.equal(deliveryStatusLabel('unexpected'), 'Unknown');
  assert.equal(isReplayableStatus('FAILED'), true);
  assert.equal(isReplayableStatus('DEAD'), true);
  assert.equal(isReplayableStatus('SUCCESS'), false);
});

test('formats attempt counts accessibly', () => {
  assert.equal(pluralizeAttempts(1), '1 attempt');
  assert.equal(pluralizeAttempts(2), '2 attempts');
});
