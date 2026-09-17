package com.kemselcuk.webhook.delivery;

import java.util.UUID;

/**
 * Compact versioned Kafka command. Business payload is loaded from
 * PostgreSQL after the delivery lease is acquired.
 */
public record DeliveryCommand(int version, UUID deliveryId) {

    public DeliveryCommand {
        if (version != 1) {
            throw new IllegalArgumentException("only delivery command version 1 is supported");
        }
        if (deliveryId == null) {
            throw new NullPointerException("deliveryId");
        }
    }
}
