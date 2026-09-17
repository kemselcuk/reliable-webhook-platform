package com.kemselcuk.webhook.delivery;

import java.util.Objects;

/**
 * HTTP status or a bounded transport failure. Response bodies are never part
 * of this result and therefore are not retained by the worker.
 */
public record DeliveryHttpResult(
        Integer httpStatus,
        DeliveryTransportFailure transportFailure
) {

    public DeliveryHttpResult {
        if ((httpStatus == null) == (transportFailure == null)) {
            throw new IllegalArgumentException("result must contain exactly one status or transport failure");
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
    }

    public static DeliveryHttpResult httpStatus(int httpStatus) {
        return new DeliveryHttpResult(httpStatus, null);
    }

    public static DeliveryHttpResult transportFailure(DeliveryTransportFailure failure) {
        return new DeliveryHttpResult(null, Objects.requireNonNull(failure, "failure"));
    }

    public boolean hasHttpStatus() {
        return httpStatus != null;
    }
}
