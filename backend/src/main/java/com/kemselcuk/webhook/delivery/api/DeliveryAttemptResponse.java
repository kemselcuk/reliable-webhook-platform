package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.domain.DeliveryAttempt;
import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptResponse(
        UUID id,
        int attemptNumber,
        DeliveryAttemptOutcome outcome,
        Integer httpStatus,
        String errorCode,
        Instant startedAt,
        Instant completedAt
) {

    public static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.getId(),
                attempt.getAttemptNumber(),
                attempt.getOutcome(),
                attempt.getHttpStatus(),
                attempt.getErrorCode(),
                attempt.getStartedAt(),
                attempt.getCompletedAt()
        );
    }
}
