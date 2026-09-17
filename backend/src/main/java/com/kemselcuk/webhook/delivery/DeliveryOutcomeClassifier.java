package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;

/**
 * HTTP failure classification seam. Phase 4 will replace this basic policy
 * with retry/backoff-aware classification.
 */
@FunctionalInterface
public interface DeliveryOutcomeClassifier {

    DeliveryAttemptOutcome classify(int httpStatus);
}
