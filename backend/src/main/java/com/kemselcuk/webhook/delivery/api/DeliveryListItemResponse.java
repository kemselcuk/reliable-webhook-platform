package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

public record DeliveryListItemResponse(
        UUID id,
        UUID eventId,
        String eventType,
        UUID endpointId,
        String endpointName,
        String endpointUrl,
        DeliveryStatus status,
        int attemptCount,
        int currentRunAttemptCount,
        Instant nextRetryAt,
        Instant createdAt,
        Instant updatedAt,
        boolean replayable
) {

    public static DeliveryListItemResponse from(Delivery delivery) {
        return new DeliveryListItemResponse(
                delivery.getId(),
                delivery.getEvent().getId(),
                delivery.getEvent().getEventType(),
                delivery.getWebhookEndpoint().getId(),
                delivery.getWebhookEndpoint().getName(),
                delivery.getWebhookEndpoint().getUrl(),
                delivery.getStatus(),
                delivery.getAttemptCount(),
                delivery.getRunAttemptCount(),
                delivery.getNextRetryAt(),
                delivery.getCreatedAt(),
                delivery.getUpdatedAt(),
                isReplayable(delivery.getStatus())
        );
    }

    static boolean isReplayable(DeliveryStatus status) {
        return status == DeliveryStatus.FAILED || status == DeliveryStatus.DEAD;
    }
}
