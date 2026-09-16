package com.kemselcuk.webhook.endpoint.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookEndpointResponse(
        UUID id,
        String name,
        String url,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
