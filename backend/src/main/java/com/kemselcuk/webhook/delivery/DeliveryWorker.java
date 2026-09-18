package com.kemselcuk.webhook.delivery;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Orchestrates one delivery while keeping the outbound HTTP call outside the
 * claim and completion transactions.
 */
@Service
public class DeliveryWorker {

    private final DeliveryClaimStore claimStore;
    private final DeliveryHttpClient httpClient;
    private final DeliveryWorkerProperties properties;
    private final DeliveryRetryPolicy retryPolicy;
    private final Clock clock;

    public DeliveryWorker(
            DeliveryClaimStore claimStore,
            DeliveryHttpClient httpClient,
            DeliveryWorkerProperties properties,
            DeliveryRetryPolicy retryPolicy,
            Clock clock
    ) {
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Process one command. A non-claimed result returns without making HTTP;
     * a claimed result is completed only after the HTTP exchange finishes.
     */
    public DeliveryWorkerResult process(UUID deliveryId) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        DeliveryClaimResult claim = claimStore.claim(
                deliveryId, clock.instant(), properties.getClaimTimeout()
        );
        if (!claim.claimed()) {
            return DeliveryWorkerResult.fromClaim(claim);
        }

        DeliveryWorkSnapshot work = claim.work();
        Instant startedAt = clock.instant();
        DeliveryHttpResult httpResult;
        try {
            httpResult = Objects.requireNonNull(httpClient.post(work), "httpResult");
        } catch (RuntimeException exception) {
            // Keep the lease from being stranded if an adapter violates its
            // result contract; only the bounded category reaches persistence.
            httpResult = DeliveryHttpResult.transportFailure(DeliveryTransportFailure.IO_FAILURE);
        }
        Instant completedAt = clock.instant();
        if (httpResult.hasHttpStatus()) {
            return processHttpResult(work, httpResult, startedAt, completedAt);
        }
        DeliveryTransportFailure transportFailure = httpResult.transportFailure();
        DeliveryRetryDecision decision = retryPolicy.decide(
                httpResult, work.currentRunAttemptNumber(), completedAt
        );
        boolean completed = claimStore.completeFailure(
                work,
                decision,
                null,
                startedAt,
                completedAt
        );
        if (!completed) {
            return DeliveryWorkerResult.staleCompletion(null, transportFailure);
        }
        return DeliveryWorkerResult.failed(decision.targetStatus(), null, transportFailure);
    }

    private DeliveryWorkerResult processHttpResult(
            DeliveryWorkSnapshot work,
            DeliveryHttpResult httpResult,
            Instant startedAt,
            Instant completedAt
    ) {
        int httpStatus = httpResult.httpStatus();
        if (httpStatus >= 200 && httpStatus <= 299) {
            boolean completed = claimStore.completeSuccess(
                    work, httpStatus, startedAt, completedAt
            );
            return completed
                    ? DeliveryWorkerResult.success(httpStatus)
                    : DeliveryWorkerResult.staleCompletion(httpStatus, null);
        }

        DeliveryRetryDecision decision = retryPolicy.decide(
                httpResult, work.currentRunAttemptNumber(), completedAt
        );
        boolean completed = claimStore.completeFailure(
                work,
                decision,
                httpStatus,
                startedAt,
                completedAt
        );
        if (!completed) {
            return DeliveryWorkerResult.staleCompletion(httpStatus, null);
        }
        return DeliveryWorkerResult.failed(decision.targetStatus(), httpStatus, null);
    }
}
