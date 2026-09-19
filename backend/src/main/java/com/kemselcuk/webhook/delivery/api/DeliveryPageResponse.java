package com.kemselcuk.webhook.delivery.api;

import java.util.List;

public record DeliveryPageResponse(
        List<DeliveryListItemResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public DeliveryPageResponse {
        items = List.copyOf(items);
    }
}
