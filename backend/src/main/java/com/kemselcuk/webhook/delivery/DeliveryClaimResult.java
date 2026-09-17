package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryStatus;

import java.util.Objects;

/**
 * Result of a delivery claim command, including a work snapshot only when a
 * lease was actually acquired.
 */
public record DeliveryClaimResult(
        DeliveryClaimDisposition disposition,
        DeliveryStatus status,
        DeliveryWorkSnapshot work
) {

    public DeliveryClaimResult {
        disposition = Objects.requireNonNull(disposition, "disposition");
        if (disposition == DeliveryClaimDisposition.CLAIMED && work == null) {
            throw new IllegalArgumentException("CLAIMED result must contain work");
        }
        if (disposition != DeliveryClaimDisposition.CLAIMED && work != null) {
            throw new IllegalArgumentException("unclaimable result must not contain work");
        }
        if (disposition == DeliveryClaimDisposition.NOT_FOUND && status != null) {
            throw new IllegalArgumentException("NOT_FOUND result must not contain status");
        }
    }

    public static DeliveryClaimResult claimed(DeliveryWorkSnapshot work) {
        return new DeliveryClaimResult(DeliveryClaimDisposition.CLAIMED,
                DeliveryStatus.PROCESSING, work);
    }

    public static DeliveryClaimResult unclaimable(
            DeliveryClaimDisposition disposition,
            DeliveryStatus status
    ) {
        if (disposition == DeliveryClaimDisposition.CLAIMED) {
            throw new IllegalArgumentException("CLAIMED requires work");
        }
        return new DeliveryClaimResult(disposition, status, null);
    }

    public boolean claimed() {
        return disposition == DeliveryClaimDisposition.CLAIMED;
    }
}
