package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DeliveryRetryDecisionTest {

    private static final Instant NEXT_RETRY = Instant.parse("2026-09-18T10:00:01Z");

    @Test
    void acceptsTheThreeCoherentDecisionShapesAndTrimsErrorCode() {
        DeliveryRetryDecision scheduled = DeliveryRetryDecision.scheduled(NEXT_RETRY, "  HTTP_503 ");
        DeliveryRetryDecision dead = DeliveryRetryDecision.dead("TIMEOUT");
        DeliveryRetryDecision permanent = DeliveryRetryDecision.permanent("HTTP_404");

        assertThat(scheduled.outcome()).isEqualTo(DeliveryAttemptOutcome.RETRYABLE_FAILURE);
        assertThat(scheduled.targetStatus()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
        assertThat(scheduled.nextRetryAt()).isEqualTo(NEXT_RETRY);
        assertThat(scheduled.errorCode()).isEqualTo("HTTP_503");
        assertThat(dead.targetStatus()).isEqualTo(DeliveryStatus.DEAD);
        assertThat(permanent.outcome()).isEqualTo(DeliveryAttemptOutcome.PERMANENT_FAILURE);
    }

    @Test
    void rejectsIncoherentOutcomeStatusAndNextRetryCombinations() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryRetryDecision(
                DeliveryAttemptOutcome.RETRYABLE_FAILURE,
                DeliveryStatus.RETRY_SCHEDULED,
                null,
                "HTTP_503"
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryRetryDecision(
                DeliveryAttemptOutcome.RETRYABLE_FAILURE,
                DeliveryStatus.DEAD,
                NEXT_RETRY,
                "HTTP_503"
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryRetryDecision(
                DeliveryAttemptOutcome.PERMANENT_FAILURE,
                DeliveryStatus.RETRY_SCHEDULED,
                NEXT_RETRY,
                "HTTP_503"
        ));
        assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryRetryDecision(
                DeliveryAttemptOutcome.SUCCESS,
                DeliveryStatus.SUCCESS,
                null,
                "HTTP_200"
        ));
    }

    @Test
    void rejectsUnboundedBlankOrUnsafeErrorCodes() {
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryRetryDecision.dead(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryRetryDecision.dead("http_500"));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryRetryDecision.dead("HTTP-500"));
        assertThatIllegalArgumentException().isThrownBy(() -> DeliveryRetryDecision.dead("X".repeat(129)));
    }
}
