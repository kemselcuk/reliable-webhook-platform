package com.kemselcuk.webhook.domain;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCESS,
    FAILED,
    DEAD
}
