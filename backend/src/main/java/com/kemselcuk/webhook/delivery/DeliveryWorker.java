package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.observability.LogContext;
import com.kemselcuk.webhook.observability.WebhookMetrics;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliveryClaimStore claimStore;
    private final DeliveryHttpClient httpClient;
    private final DeliveryWorkerProperties properties;
    private final DeliveryRetryPolicy retryPolicy;
    private final Clock clock;
    private final WebhookMetrics metrics;

    public DeliveryWorker(
            DeliveryClaimStore claimStore,
            DeliveryHttpClient httpClient,
            DeliveryWorkerProperties properties,
            DeliveryRetryPolicy retryPolicy,
            Clock clock
    ) {
        this(claimStore, httpClient, properties, retryPolicy, clock, WebhookMetrics.noop());
    }

    @Autowired
    public DeliveryWorker(
            DeliveryClaimStore claimStore,
            DeliveryHttpClient httpClient,
            DeliveryWorkerProperties properties,
            DeliveryRetryPolicy retryPolicy,
            Clock clock,
            WebhookMetrics metrics
    ) {
        this.claimStore = Objects.requireNonNull(claimStore, "claimStore");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
        metrics.recordClaim(claim.disposition());
        if (!claim.claimed()) {
            DeliveryWorkerResult result = DeliveryWorkerResult.fromClaim(claim);
            metrics.recordWorkerOutcome(result.disposition());
            try (LogContext ignored = LogContext.delivery(deliveryId)) {
                LOGGER.info("delivery skipped disposition={}", result.disposition());
            }
            return result;
        }

        DeliveryWorkSnapshot work = claim.work();
        try (LogContext ignored = LogContext.delivery(work.deliveryId(), work.eventId())) {
            Instant startedAt = clock.instant();
            DeliveryHttpResult httpResult;
            Timer.Sample httpTimer = metrics.startHttpTimer();
            try {
                httpResult = Objects.requireNonNull(httpClient.post(work), "httpResult");
            } catch (RuntimeException exception) {
                // Keep the lease from being stranded if an adapter violates its
                // result contract; only the bounded category reaches persistence.
                httpResult = DeliveryHttpResult.transportFailure(DeliveryTransportFailure.IO_FAILURE);
            }
            metrics.recordHttpResult(httpTimer, httpResult);
            Instant completedAt = clock.instant();
            DeliveryWorkerResult result;
            if (httpResult.hasHttpStatus()) {
                result = processHttpResult(work, httpResult, startedAt, completedAt);
            } else {
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
                if (completed) {
                    metrics.recordRetry(decision.targetStatus());
                }
                result = completed
                        ? DeliveryWorkerResult.failed(decision.targetStatus(), null, transportFailure)
                        : DeliveryWorkerResult.staleCompletion(null, transportFailure);
            }
            metrics.recordWorkerOutcome(result.disposition());
            LOGGER.info(
                    "delivery completed disposition={} delivery_status={} http_status={} transport_failure={}",
                    result.disposition(), result.deliveryStatus(), result.httpStatus(), result.transportFailure()
            );
            return result;
        }
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
        metrics.recordRetry(decision.targetStatus());
        return DeliveryWorkerResult.failed(decision.targetStatus(), httpStatus, null);
    }
}
