package com.kemselcuk.webhook.delivery;

import java.util.UUID;

/** Result of attempting the persistence-side manual replay transition. */
public record DeliveryReplayResult(
        Disposition disposition,
        UUID deliveryId,
        Integer attemptCount,
        Integer currentRunAttemptCount
) {

    public DeliveryReplayResult {
        if (disposition == null) {
            throw new IllegalArgumentException("disposition is required");
        }
        if (disposition == Disposition.REPLAYED) {
            if (deliveryId == null || attemptCount == null || attemptCount < 0
                    || currentRunAttemptCount == null || currentRunAttemptCount != 0) {
                throw new IllegalArgumentException("replayed result must contain coherent counters");
            }
        } else if (deliveryId != null || attemptCount != null || currentRunAttemptCount != null) {
            throw new IllegalArgumentException("non-replayed result must not contain replay state");
        }
    }

    public enum Disposition {
        NOT_FOUND,
        DISABLED,
        INELIGIBLE,
        REPLAYED
    }

    public static DeliveryReplayResult notFound() {
        return new DeliveryReplayResult(Disposition.NOT_FOUND, null, null, null);
    }

    public static DeliveryReplayResult disabled() {
        return new DeliveryReplayResult(Disposition.DISABLED, null, null, null);
    }

    public static DeliveryReplayResult ineligible() {
        return new DeliveryReplayResult(Disposition.INELIGIBLE, null, null, null);
    }

    public static DeliveryReplayResult replayed(UUID deliveryId, int attemptCount) {
        return new DeliveryReplayResult(Disposition.REPLAYED, deliveryId, attemptCount, 0);
    }
}
