package com.kemselcuk.webhook.outbox;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable work claimed from PostgreSQL for one publisher cycle.
 */
public record OutboxClaim(
        UUID id,
        UUID deliveryId,
        JsonNode payload,
        UUID claimToken
) {

    public OutboxClaim {
        id = Objects.requireNonNull(id, "id");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        payload = Objects.requireNonNull(payload, "payload").deepCopy();
        claimToken = Objects.requireNonNull(claimToken, "claimToken");
    }
}
