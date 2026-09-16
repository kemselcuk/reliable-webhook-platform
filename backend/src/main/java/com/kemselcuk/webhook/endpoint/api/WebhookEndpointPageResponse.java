package com.kemselcuk.webhook.endpoint.api;

import java.util.List;

public record WebhookEndpointPageResponse(
        List<WebhookEndpointResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public WebhookEndpointPageResponse {
        items = List.copyOf(items);
    }
}
