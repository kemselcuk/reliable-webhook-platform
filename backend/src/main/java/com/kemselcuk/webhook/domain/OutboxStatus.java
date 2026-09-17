package com.kemselcuk.webhook.domain;

public enum OutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED
}
