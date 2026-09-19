/**
 * @typedef {{title?: string, detail?: string, code?: string, fieldErrors?: Record<string, string>}} ProblemDetail
 */

/**
 * @param {unknown} value
 * @returns {value is Record<string, unknown>}
 */
function isRecord(value) {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * @param {unknown} value
 * @returns {ProblemDetail | null}
 */
export function parseProblemDetail(value) {
  if (!isRecord(value)) {
    return null;
  }

  /** @type {ProblemDetail} */
  const problem = {};
  if (typeof value.title === 'string') {
    problem.title = value.title;
  }
  if (typeof value.detail === 'string') {
    problem.detail = value.detail;
  }
  if (typeof value.code === 'string') {
    problem.code = value.code;
  }

  if (isRecord(value.fieldErrors)) {
    /** @type {Record<string, string>} */
    const fieldErrors = {};
    Object.entries(value.fieldErrors)
      .slice(0, 5)
      .forEach(([field, message]) => {
        if (typeof message === 'string') {
          fieldErrors[field] = message;
        }
      });
    if (Object.keys(fieldErrors).length > 0) {
      problem.fieldErrors = fieldErrors;
    }
  }

  return problem;
}

/**
 * @param {string} text
 * @returns {{ok: true, value: Record<string, unknown>} | {ok: false, error: string}}
 */
export function parseJsonPayload(text) {
  if (!text.trim()) {
    return { ok: false, error: 'Payload is required.' };
  }

  try {
    const value = JSON.parse(text);
    if (!isRecord(value)) {
      return { ok: false, error: 'Payload must be a JSON object.' };
    }
    return { ok: true, value };
  } catch {
    return { ok: false, error: 'Payload must be valid JSON.' };
  }
}

/**
 * Validate the backend's byte-based endpoint secret contract without putting
 * the submitted secret into an error message.
 *
 * @param {string} secret
 * @returns {string | null}
 */
export function signingSecretValidationMessage(secret) {
  const byteLength = new TextEncoder().encode(secret).byteLength;
  if (byteLength < 32 || byteLength > 512) {
    return 'Signing secret must contain between 32 and 512 UTF-8 bytes.';
  }
  return null;
}

const knownProblemCodes = new Set([
  'VALIDATION_ERROR',
  'MALFORMED_JSON',
  'ENDPOINT_NAME_CONFLICT',
  'ENDPOINT_NOT_FOUND',
  'ENDPOINT_DISABLED',
  'DELIVERY_NOT_FOUND',
  'DELIVERY_NOT_REPLAYABLE',
  'IDEMPOTENCY_KEY_CONFLICT',
]);

/**
 * @param {ProblemDetail | null} problem
 * @param {string} [fallback]
 * @returns {string}
 */
export function userMessageForProblem(problem, fallback = 'The request could not be completed.') {
  if (!problem || !problem.code || !knownProblemCodes.has(problem.code)) {
    return fallback;
  }

  const baseMessage = problem.detail?.trim() || problem.title?.trim() || fallback;
  const fieldMessages = problem.fieldErrors
    ? Object.entries(problem.fieldErrors)
        .slice(0, 5)
        .map(([field, message]) => `${field}: ${message}`)
        .join(' ')
    : '';

  return fieldMessages ? `${baseMessage} ${fieldMessages}` : baseMessage;
}
