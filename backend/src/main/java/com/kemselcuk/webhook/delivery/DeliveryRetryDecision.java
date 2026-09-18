package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;

import java.time.Instant;
import java.util.Objects;

/** Immutable, bounded output of retry classification. */
public record DeliveryRetryDecision(
        DeliveryAttemptOutcome outcome,
        DeliveryStatus targetStatus,
        Instant nextRetryAt,
        String errorCode
) {

    public DeliveryRetryDecision {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(targetStatus, "targetStatus");
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        errorCode = errorCode.trim();
        if (errorCode.isEmpty() || errorCode.length() > 128
                || !errorCode.chars().allMatch(DeliveryRetryDecision::isSafeErrorCodeCharacter)) {
            throw new IllegalArgumentException("errorCode must be a bounded uppercase category");
        }

        boolean scheduled = outcome == DeliveryAttemptOutcome.RETRYABLE_FAILURE
                && targetStatus == DeliveryStatus.RETRY_SCHEDULED
                && nextRetryAt != null;
        boolean dead = outcome == DeliveryAttemptOutcome.RETRYABLE_FAILURE
                && targetStatus == DeliveryStatus.DEAD
                && nextRetryAt == null;
        boolean permanent = outcome == DeliveryAttemptOutcome.PERMANENT_FAILURE
                && targetStatus == DeliveryStatus.FAILED
                && nextRetryAt == null;
        if (!scheduled && !dead && !permanent) {
            throw new IllegalArgumentException("incoherent retry decision state");
        }
    }

    public static DeliveryRetryDecision scheduled(Instant nextRetryAt, String errorCode) {
        return new DeliveryRetryDecision(
                DeliveryAttemptOutcome.RETRYABLE_FAILURE,
                DeliveryStatus.RETRY_SCHEDULED,
                Objects.requireNonNull(nextRetryAt, "nextRetryAt"),
                errorCode
        );
    }

    public static DeliveryRetryDecision dead(String errorCode) {
        return new DeliveryRetryDecision(
                DeliveryAttemptOutcome.RETRYABLE_FAILURE,
                DeliveryStatus.DEAD,
                null,
                errorCode
        );
    }

    public static DeliveryRetryDecision permanent(String errorCode) {
        return new DeliveryRetryDecision(
                DeliveryAttemptOutcome.PERMANENT_FAILURE,
                DeliveryStatus.FAILED,
                null,
                errorCode
        );
    }

    private static boolean isSafeErrorCodeCharacter(int character) {
        return character == '_'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9';
    }
}
