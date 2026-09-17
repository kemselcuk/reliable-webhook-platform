package com.kemselcuk.webhook.delivery;

/**
 * Bounded result categories for a delivery command that cannot be claimed.
 */
public enum DeliveryClaimDisposition {
    CLAIMED,
    NOT_FOUND,
    TERMINAL,
    BUSY,
    DISABLED,
    INELIGIBLE
}
