package com.kemselcuk.webhook.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface OutboxClaimOperations {

    List<OutboxClaim> claimBatch(int batchSize, Instant now, Duration claimTimeout);

    boolean markPublished(OutboxClaim claim, Instant publishedAt);

    boolean releaseForRetry(
            OutboxClaim claim,
            Instant now,
            Instant availableAt,
            String failureCategory
    );
}
