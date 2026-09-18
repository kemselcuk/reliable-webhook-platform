package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.delivery.DeliveryReplayResult;
import com.kemselcuk.webhook.domain.DeliveryStatus;

import java.util.UUID;

public record DeliveryReplayResponse(
        UUID deliveryId,
        DeliveryStatus status,
        int attemptCount,
        int currentRunAttemptCount
) {
    public static DeliveryReplayResponse from(DeliveryReplayResult result) {
        return new DeliveryReplayResponse(
                result.deliveryId(),
                DeliveryStatus.PENDING,
                result.attemptCount(),
                result.currentRunAttemptCount()
        );
    }
}
