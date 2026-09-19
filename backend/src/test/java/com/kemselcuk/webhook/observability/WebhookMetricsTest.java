package com.kemselcuk.webhook.observability;

import com.kemselcuk.webhook.delivery.DeliveryClaimDisposition;
import com.kemselcuk.webhook.delivery.DeliveryHttpResult;
import com.kemselcuk.webhook.delivery.DeliveryWorkerDisposition;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WebhookMetricsTest {

    @Test
    void recordsBoundedOutcomesAndHttpLatencyWithoutIdentifierTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WebhookMetrics metrics = new WebhookMetrics(registry, mock(JdbcTemplate.class));

        metrics.eventCreated(2);
        metrics.recordClaim(DeliveryClaimDisposition.BUSY);
        metrics.recordWorkerOutcome(DeliveryWorkerDisposition.SUCCESS);
        metrics.recordRetry(DeliveryStatus.RETRY_SCHEDULED);
        metrics.recordRetry(DeliveryStatus.DEAD);
        metrics.recordKafkaCommand("delivery-id-is-not-a-label");
        metrics.recordOutboxPublish("broker-error-is-not-a-label");

        Timer.Sample sample = metrics.startHttpTimer();
        metrics.recordHttpResult(sample, DeliveryHttpResult.httpStatus(503));

        assertThat(registry.get("webhook.events.accepted").counter().count()).isEqualTo(1);
        assertThat(registry.get("webhook.delivery.intents").counter().count()).isEqualTo(2);
        assertThat(registry.get("webhook.delivery.claims")
                .tag("outcome", "busy").counter().count()).isEqualTo(1);
        assertThat(registry.get("webhook.delivery.outcomes")
                .tag("outcome", "success").counter().count()).isEqualTo(1);
        assertThat(registry.get("webhook.delivery.retries")
                .tag("outcome", "retry_scheduled").counter().count()).isEqualTo(1);
        assertThat(registry.get("webhook.delivery.retries")
                .tag("outcome", "dead").counter().count()).isEqualTo(1);
        assertThat(registry.get("webhook.delivery.http.duration")
                .tag("result", "http")
                .tag("status_class", "5xx")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("webhook.kafka.commands")
                .tag("result", "other").counter().count()).isEqualTo(1);
        assertThat(registry.get("webhook.outbox.publishes")
                .tag("result", "other").counter().count()).isEqualTo(1);

        Set<String> forbiddenTags = Set.of(
                "event_id", "delivery_id", "endpoint_url", "url", "exception", "message"
        );
        assertThat(registry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .map(tag -> tag.getKey())
                .noneMatch(forbiddenTags::contains)).isTrue();
    }
}
