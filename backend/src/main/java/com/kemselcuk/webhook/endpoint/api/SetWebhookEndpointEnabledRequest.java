package com.kemselcuk.webhook.endpoint.api;

import jakarta.validation.constraints.NotNull;

public record SetWebhookEndpointEnabledRequest(
        @NotNull Boolean enabled
) {
}
