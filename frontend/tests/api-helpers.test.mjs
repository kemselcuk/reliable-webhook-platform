import test from 'node:test';
import assert from 'node:assert/strict';
import {
  parseJsonPayload,
  parseProblemDetail,
  signingSecretValidationMessage,
  userMessageForProblem,
} from '../src/api-helpers.js';

test('parses a JSON object payload', () => {
  assert.deepEqual(parseJsonPayload('{"orderId":"order-123"}'), {
    ok: true,
    value: { orderId: 'order-123' },
  });
});

test('rejects invalid, empty, and non-object JSON payloads', () => {
  assert.deepEqual(parseJsonPayload(''), { ok: false, error: 'Payload is required.' });
  assert.deepEqual(parseJsonPayload('{"orderId":'), { ok: false, error: 'Payload must be valid JSON.' });
  assert.deepEqual(parseJsonPayload('[]'), { ok: false, error: 'Payload must be a JSON object.' });
});

test('turns a known ProblemDetail into a bounded actionable message', () => {
  const problem = parseProblemDetail({
    title: 'Request validation failed',
    detail: 'One or more request fields are invalid.',
    code: 'VALIDATION_ERROR',
    fieldErrors: {
      type: 'must not be blank',
      payload: 'must not be null',
    },
    internal: 'must not be shown',
  });

  assert.equal(
    userMessageForProblem(problem),
    'One or more request fields are invalid. type: must not be blank payload: must not be null',
  );
});

test('uses a generic message for unknown or malformed problems', () => {
  assert.equal(userMessageForProblem(parseProblemDetail({ code: 'INTERNAL_ERROR', detail: 'stack trace' }), 'Try again'), 'Try again');
  assert.equal(userMessageForProblem(parseProblemDetail('not a problem'), 'Try again'), 'Try again');
});

test('validates endpoint secrets by UTF-8 byte length without exposing material', () => {
  assert.equal(signingSecretValidationMessage('é'.repeat(16)), null);
  assert.equal(
    signingSecretValidationMessage('s'.repeat(31)),
    'Signing secret must contain between 32 and 512 UTF-8 bytes.',
  );
  assert.equal(
    signingSecretValidationMessage('é'.repeat(257)),
    'Signing secret must contain between 32 and 512 UTF-8 bytes.',
  );
});
