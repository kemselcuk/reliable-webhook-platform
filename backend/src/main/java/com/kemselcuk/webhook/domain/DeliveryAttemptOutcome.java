package com.kemselcuk.webhook.domain;

public enum DeliveryAttemptOutcome {
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE
}
