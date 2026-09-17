package com.kemselcuk.webhook.delivery;

/**
 * Bounded transport categories safe to pass to persistence and metrics.
 */
public enum DeliveryTransportFailure {
    TIMEOUT,
    CONNECTION,
    IO_FAILURE
}
