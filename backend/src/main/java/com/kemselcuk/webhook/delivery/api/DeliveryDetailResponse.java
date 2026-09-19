package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.domain.Delivery;

import java.util.List;

public record DeliveryDetailResponse(
        DeliveryListItemResponse delivery,
        List<DeliveryAttemptResponse> attempts
) {

    public DeliveryDetailResponse {
        attempts = List.copyOf(attempts);
    }

    public static DeliveryDetailResponse from(
            Delivery delivery,
            List<DeliveryAttemptResponse> attempts
    ) {
        return new DeliveryDetailResponse(
                DeliveryListItemResponse.from(delivery),
                attempts
        );
    }
}
