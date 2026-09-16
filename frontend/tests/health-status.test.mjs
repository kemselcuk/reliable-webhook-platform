import test from 'node:test';
import assert from 'node:assert/strict';
import { healthStatusLabel } from '../src/health-status.js';

test('formats the healthy API status for the UI', () => {
  assert.equal(healthStatusLabel('UP'), 'Healthy');
});

test('keeps non-healthy status visible to the operator', () => {
  assert.equal(healthStatusLabel('DOWN'), 'Unavailable (DOWN)');
  assert.equal(healthStatusLabel(undefined), 'Unavailable');
});
