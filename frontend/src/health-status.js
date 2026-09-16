/**
 * @param {string | undefined} status
 * @returns {string}
 */
export function healthStatusLabel(status) {
  if (status === 'UP') {
    return 'Healthy';
  }

  return status ? `Unavailable (${status})` : 'Unavailable';
}
