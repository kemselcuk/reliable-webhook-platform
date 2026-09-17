package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryStatus;

import java.util.Objects;

/**
 * Stateless worker result carrying only bounded status/category data.
 * Response bodies are deliberately absent.
 */
public record DeliveryWorkerResult(
        DeliveryWorkerDisposition disposition,
        DeliveryStatus deliveryStatus,
        Integer httpStatus,
        DeliveryTransportFailure transportFailure
) {

    public DeliveryWorkerResult {
        disposition = Objects.requireNonNull(disposition, "disposition");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        if (disposition == DeliveryWorkerDisposition.SUCCESS && httpStatus == null) {
            throw new IllegalArgumentException("SUCCESS requires httpStatus");
        }
        if (disposition == DeliveryWorkerDisposition.FAILED
                && httpStatus == null && transportFailure == null) {
            throw new IllegalArgumentException("FAILED requires status or transport failure");
        }
        if (transportFailure != null && httpStatus != null) {
            throw new IllegalArgumentException("result must not contain both status and transport failure");
        }
    }

    public static DeliveryWorkerResult fromClaim(DeliveryClaimResult claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.claimed()) {
            throw new IllegalArgumentException("claimed result must be completed by the worker");
        }
        DeliveryWorkerDisposition disposition = switch (claim.disposition()) {
            case NOT_FOUND -> DeliveryWorkerDisposition.NOT_FOUND;
            case TERMINAL -> DeliveryWorkerDisposition.TERMINAL;
            case BUSY -> DeliveryWorkerDisposition.BUSY;
            case DISABLED -> DeliveryWorkerDisposition.DISABLED;
            case INELIGIBLE -> DeliveryWorkerDisposition.INELIGIBLE;
            case CLAIMED -> throw new IllegalArgumentException("claimed result must be completed");
        };
        return new DeliveryWorkerResult(disposition, claim.status(), null, null);
    }

    public static DeliveryWorkerResult success(int httpStatus) {
        return new DeliveryWorkerResult(
                DeliveryWorkerDisposition.SUCCESS,
                DeliveryStatus.SUCCESS,
                httpStatus,
                null
        );
    }

    public static DeliveryWorkerResult failed(
            DeliveryStatus deliveryStatus,
            Integer httpStatus,
            DeliveryTransportFailure transportFailure
    ) {
        return new DeliveryWorkerResult(
                DeliveryWorkerDisposition.FAILED,
                deliveryStatus,
                httpStatus,
                transportFailure
        );
    }

    public static DeliveryWorkerResult staleCompletion(
            Integer httpStatus,
            DeliveryTransportFailure transportFailure
    ) {
        return new DeliveryWorkerResult(
                DeliveryWorkerDisposition.STALE_COMPLETION,
                null,
                httpStatus,
                transportFailure
        );
    }
}
