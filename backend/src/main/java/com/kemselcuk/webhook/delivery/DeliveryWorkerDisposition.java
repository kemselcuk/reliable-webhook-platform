package com.kemselcuk.webhook.delivery;

/**
 * Bounded process outcomes returned to a future Kafka listener or caller.
 */
public enum DeliveryWorkerDisposition {
    SUCCESS,
    FAILED,
    NOT_FOUND,
    TERMINAL,
    BUSY,
    DISABLED,
    INELIGIBLE,
    STALE_COMPLETION
}
