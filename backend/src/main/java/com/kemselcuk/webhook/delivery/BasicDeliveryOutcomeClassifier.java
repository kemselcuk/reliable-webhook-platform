package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import org.springframework.stereotype.Component;

/**
 * Temporary Phase 3 policy: 429/5xx are retryable; every other non-2xx
 * response (including a redirect because redirects are disabled) is
 * permanent/basic failure.
 */
@Component
public class BasicDeliveryOutcomeClassifier implements DeliveryOutcomeClassifier {

    @Override
    public DeliveryAttemptOutcome classify(int httpStatus) {
        if (httpStatus == 429 || httpStatus >= 500) {
            return DeliveryAttemptOutcome.RETRYABLE_FAILURE;
        }
        if (httpStatus >= 100 && httpStatus <= 599 && (httpStatus < 200 || httpStatus > 299)) {
            return DeliveryAttemptOutcome.PERMANENT_FAILURE;
        }
        throw new IllegalArgumentException("only non-2xx HTTP statuses can be classified");
    }
}
