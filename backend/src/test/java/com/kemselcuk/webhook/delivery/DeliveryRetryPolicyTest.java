package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class DeliveryRetryPolicyTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void classifiesTransport429And5xxAsRetryable() {
        DeliveryRetryDecision transport = policy(0.5).decide(
                DeliveryHttpResult.transportFailure(DeliveryTransportFailure.TIMEOUT),
                1,
                COMPLETED_AT
        );
        DeliveryRetryDecision throttled = policy(0.5).decide(
                DeliveryHttpResult.httpStatus(429), 1, COMPLETED_AT
        );
        DeliveryRetryDecision server = policy(0.5).decide(
                DeliveryHttpResult.httpStatus(503), 1, COMPLETED_AT
        );

        assertThat(transport.targetStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(transport.outcome()).isEqualTo(DeliveryAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(transport.errorCode()).isEqualTo("TIMEOUT");
        assertThat(throttled.errorCode()).isEqualTo("HTTP_429");
        assertThat(server.errorCode()).isEqualTo("HTTP_503");
    }

    @Test
    void classifiesOtherHttpFailuresAsPermanentFailed() {
        for (int status : new int[]{100, 301, 400, 404, 499}) {
            DeliveryRetryDecision decision = policy(0.5).decide(
                    DeliveryHttpResult.httpStatus(status), 1, COMPLETED_AT
            );
            assertThat(decision.outcome()).isEqualTo(DeliveryAttemptOutcome.PERMANENT_FAILURE);
            assertThat(decision.targetStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(decision.nextRetryAt()).isNull();
            assertThat(decision.errorCode()).isEqualTo("HTTP_" + status);
        }
    }

    @Test
    void maxAttemptBecomesDeadAndTheAttemptBeforeItIsScheduled() {
        DeliveryRetryProperties properties = properties();
        properties.setMaxAttempts(3);
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(properties, () -> 0.5);

        assertThat(policy.decide(DeliveryHttpResult.httpStatus(500), 2, COMPLETED_AT)
                .targetStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(policy.decide(DeliveryHttpResult.httpStatus(500), 3, COMPLETED_AT)
                .targetStatus()).isEqualTo(DeliveryStatus.DEAD);
    }

    @Test
    void exponentialDelayIsCappedBeforeLargeExponents() {
        DeliveryRetryProperties properties = properties();
        properties.setInitialDelay(Duration.ofSeconds(2));
        properties.setMaxDelay(Duration.ofSeconds(10));
        properties.setJitterFactor(0.0);
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(properties, () -> 0.5);

        assertThat(delay(policy, 1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(delay(policy, 2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(delay(policy, 3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(delay(policy, 4)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterUsesLowMiddleAndHighMultiplicativeBounds() {
        DeliveryRetryProperties properties = properties();
        properties.setInitialDelay(Duration.ofSeconds(10));
        properties.setMaxDelay(Duration.ofMinutes(1));
        properties.setJitterFactor(0.20);

        assertThat(delay(new DeliveryRetryPolicy(properties, () -> 0.0), 1))
                .isEqualTo(Duration.ofSeconds(8));
        assertThat(delay(new DeliveryRetryPolicy(properties, () -> 0.5), 1))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(delay(new DeliveryRetryPolicy(properties, () -> 1.0), 1))
                .isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void retryAfterDeltaIsMinimumAndFinalDelayIsCapped() {
        DeliveryRetryProperties properties = properties();
        properties.setJitterFactor(0.0);
        properties.setMaxDelay(Duration.ofSeconds(30));
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(properties, () -> 0.5);

        assertThat(policy.decide(
                DeliveryHttpResult.httpStatus(429, " 20 "), 1, COMPLETED_AT
        ).nextRetryAt()).isEqualTo(COMPLETED_AT.plusSeconds(20));
        assertThat(policy.decide(
                DeliveryHttpResult.httpStatus(429, "90"), 1, COMPLETED_AT
        ).nextRetryAt()).isEqualTo(COMPLETED_AT.plusSeconds(30));
    }

    @Test
    void retryAfterDateIsMinimumAndMalformedOrPastFallsBack() {
        DeliveryRetryProperties properties = properties();
        properties.setJitterFactor(0.0);
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(properties, () -> 0.5);
        String futureDate = ZonedDateTime.ofInstant(
                COMPLETED_AT.plusSeconds(25), ZoneOffset.UTC
        ).format(DateTimeFormatter.RFC_1123_DATE_TIME);

        assertThat(policy.decide(
                DeliveryHttpResult.httpStatus(503, futureDate), 1, COMPLETED_AT
        ).nextRetryAt()).isEqualTo(COMPLETED_AT.plusSeconds(25));
        assertThat(policy.decide(
                DeliveryHttpResult.httpStatus(503, "not-a-date"), 1, COMPLETED_AT
        ).nextRetryAt()).isEqualTo(COMPLETED_AT.plusSeconds(1));
        assertThat(policy.decide(
                DeliveryHttpResult.httpStatus(503, "-1"), 1, COMPLETED_AT
        ).nextRetryAt()).isEqualTo(COMPLETED_AT.plusSeconds(1));
        assertThat(policy.decide(
                DeliveryHttpResult.httpStatus(503, "Wed, 18 Sep 2024 10:00:00 GMT"),
                1,
                COMPLETED_AT
        ).nextRetryAt()).isEqualTo(COMPLETED_AT.plusSeconds(1));
    }

    @Test
    void rejectsInvalidInputsConfigurationAndJitterSamples() {
        DeliveryRetryPolicy policy = policy(0.5);
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy.decide(null, 1, COMPLETED_AT));
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy.decide(DeliveryHttpResult.httpStatus(500), 0, COMPLETED_AT));
        assertThatIllegalArgumentException().isThrownBy(() ->
                policy.decide(DeliveryHttpResult.httpStatus(204), 1, COMPLETED_AT));
        assertThatIllegalStateException().isThrownBy(() ->
                new DeliveryRetryPolicy(properties(), () -> Double.NaN)
                        .decide(DeliveryHttpResult.httpStatus(500), 1, COMPLETED_AT));
        assertThatIllegalStateException().isThrownBy(() ->
                new DeliveryRetryPolicy(properties(), () -> 1.1)
                        .decide(DeliveryHttpResult.httpStatus(500), 1, COMPLETED_AT));
        DeliveryRetryProperties invalid = properties();
        invalid.setJitterFactor(1.1);
        assertThatIllegalStateException().isThrownBy(() ->
                new DeliveryRetryPolicy(invalid, () -> 0.5)
                        .decide(DeliveryHttpResult.httpStatus(500), 1, COMPLETED_AT));
    }

    private static Duration delay(DeliveryRetryPolicy policy, int attempt) {
        return Duration.between(COMPLETED_AT, policy.decide(
                DeliveryHttpResult.httpStatus(500), attempt, COMPLETED_AT
        ).nextRetryAt());
    }

    private static DeliveryRetryPolicy policy(double sample) {
        return new DeliveryRetryPolicy(properties(), () -> sample);
    }

    private static DeliveryRetryProperties properties() {
        return new DeliveryRetryProperties();
    }
}
