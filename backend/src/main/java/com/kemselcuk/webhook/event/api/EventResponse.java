package com.kemselcuk.webhook.event.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String type,
        JsonNode payload,
        List<UUID> deliveryIds,
        Instant createdAt
) {

    public EventResponse {
        deliveryIds = List.copyOf(deliveryIds);
    }
}
