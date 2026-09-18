package com.kemselcuk.webhook.delivery;

import java.util.Objects;

/**
 * HTTP status or a bounded transport failure. Response bodies are never part
 * of this result and therefore are not retained by the worker.
 */
public record DeliveryHttpResult(
        Integer httpStatus,
        DeliveryTransportFailure transportFailure,
        String retryAfter
) {

    /** Compatibility constructor for callers that do not need response metadata. */
    public DeliveryHttpResult(Integer httpStatus, DeliveryTransportFailure transportFailure) {
        this(httpStatus, transportFailure, null);
    }

    public DeliveryHttpResult {
        if ((httpStatus == null) == (transportFailure == null)) {
            throw new IllegalArgumentException("result must contain exactly one status or transport failure");
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        if (transportFailure != null && retryAfter != null) {
            throw new IllegalArgumentException("retryAfter is only valid for HTTP results");
        }
        if (retryAfter != null) {
            retryAfter = retryAfter.trim();
            if (retryAfter.isEmpty() || retryAfter.length() > 128) {
                retryAfter = null;
            }
        }
    }

    public static DeliveryHttpResult httpStatus(int httpStatus) {
        return httpStatus(httpStatus, null);
    }

    public static DeliveryHttpResult httpStatus(int httpStatus, String retryAfter) {
        return new DeliveryHttpResult(httpStatus, null, retryAfter);
    }

    public static DeliveryHttpResult transportFailure(DeliveryTransportFailure failure) {
        return new DeliveryHttpResult(null, Objects.requireNonNull(failure, "failure"), null);
    }

    public boolean hasHttpStatus() {
        return httpStatus != null;
    }
}
