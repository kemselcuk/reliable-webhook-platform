package com.kemselcuk.webhook.delivery;

/**
 * Bounded poison-command categories. These values are safe to log without
 * including command contents.
 */
public enum DeliveryCommandErrorCategory {
    MALFORMED,
    UNSUPPORTED_VERSION,
    INVALID_DELIVERY_ID,
    KEY_MISMATCH
}
