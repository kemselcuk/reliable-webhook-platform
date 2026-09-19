/** @type {Record<string, string>} */
const statusLabels = {
  PENDING: 'Pending',
  PROCESSING: 'Processing',
  RETRY_SCHEDULED: 'Retry scheduled',
  SUCCESS: 'Delivered',
  FAILED: 'Failed',
  DEAD: 'Dead',
};

/**
 * @param {string} status
 * @returns {string}
 */
export function deliveryStatusLabel(status) {
  return statusLabels[status] ?? 'Unknown';
}

/**
 * @param {string} status
 * @returns {boolean}
 */
export function isReplayableStatus(status) {
  return status === 'FAILED' || status === 'DEAD';
}

/**
 * @param {number} count
 * @returns {string}
 */
export function pluralizeAttempts(count) {
  return `${count} ${count === 1 ? 'attempt' : 'attempts'}`;
}
