package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, detached delivery work loaded while acquiring a delivery lease.
 *
 * <p>The JSON tree is copied both on construction and when read so callers
 * cannot mutate a snapshot retained by another worker or by the store.</p>
 */
public record DeliveryWorkSnapshot(
        UUID deliveryId,
        UUID eventId,
        UUID endpointId,
        String eventType,
        JsonNode payload,
        String endpointUrl,
        boolean endpointEnabled,
        UUID claimToken,
        int nextAttemptNumber,
        int currentRunAttemptNumber
) {

    /** Compatibility constructor for callers created before retry state. */
    public DeliveryWorkSnapshot(
            UUID deliveryId,
            UUID eventId,
            UUID endpointId,
            String eventType,
            JsonNode payload,
            String endpointUrl,
            boolean endpointEnabled,
            UUID claimToken,
            int nextAttemptNumber
    ) {
        this(
                deliveryId, eventId, endpointId, eventType, payload, endpointUrl,
                endpointEnabled, claimToken, nextAttemptNumber, nextAttemptNumber
        );
    }

    public DeliveryWorkSnapshot {
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        eventId = Objects.requireNonNull(eventId, "eventId");
        endpointId = Objects.requireNonNull(endpointId, "endpointId");
        eventType = requireText(eventType, "eventType");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        endpointUrl = requireText(endpointUrl, "endpointUrl");
        claimToken = Objects.requireNonNull(claimToken, "claimToken");
        if (nextAttemptNumber < 1) {
            throw new IllegalArgumentException("nextAttemptNumber must be positive");
        }
        if (currentRunAttemptNumber < 1) {
            throw new IllegalArgumentException("currentRunAttemptNumber must be positive");
        }
    }

    /**
     * Return a detached tree rather than the tree retained in this record.
     */
    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
