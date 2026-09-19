package com.kemselcuk.webhook.observability;

import com.kemselcuk.webhook.delivery.DeliveryClaimDisposition;
import com.kemselcuk.webhook.delivery.DeliveryHttpResult;
import com.kemselcuk.webhook.delivery.DeliveryWorkerDisposition;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * Bounded-cardinality application metrics for the durable delivery paths.
 *
 * <p>All tag values come from finite enums or status classes. Identifiers,
 * URLs, payloads, exception messages, and raw Kafka metadata never become
 * metric labels.</p>
 */
@Component
public class WebhookMetrics {

    private static final String OUTCOME_TAG = "outcome";
    private static final String RESULT_TAG = "result";
    private static final String STATUS_CLASS_TAG = "status_class";

    private final MeterRegistry registry;
    private final Counter eventsCreated;
    private final Counter deliveriesCreated;
    private final Counter deliveryDead;

    private WebhookMetrics(
            MeterRegistry registry,
            JdbcTemplate jdbcTemplate,
            boolean registerMeters
    ) {
        this.registry = registry;
        if (!registerMeters) {
            eventsCreated = null;
            deliveriesCreated = null;
            deliveryDead = null;
            return;
        }

        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        eventsCreated = Counter.builder("webhook.events.accepted")
                .description("Events durably accepted by the API")
                .register(registry);
        deliveriesCreated = Counter.builder("webhook.delivery.intents")
                .description("Delivery intents created with events")
                .register(registry);
        deliveryDead = Counter.builder("webhook.delivery.dead")
                .description("Deliveries transitioned to DEAD")
                .register(registry);

        registry.gauge(
                "webhook.outbox.backlog",
                jdbcTemplate,
                ignored -> queryCount(jdbcTemplate, """
                        SELECT COUNT(*)
                        FROM outbox_events
                        WHERE status = 'PENDING'
                        """)
        );
        registry.gauge(
                "webhook.delivery.retry.backlog",
                jdbcTemplate,
                ignored -> queryCount(jdbcTemplate, """
                        SELECT COUNT(*)
                        FROM deliveries
                        WHERE status = 'RETRY_SCHEDULED'
                        """)
        );
    }

    @Autowired
    public WebhookMetrics(MeterRegistry registry, JdbcTemplate jdbcTemplate) {
        this(registry, jdbcTemplate, true);
    }

    /** Constructor for deterministic unit tests and legacy direct callers. */
    public static WebhookMetrics noop() {
        return new WebhookMetrics(null, null, false);
    }

    public void eventCreated(int deliveryCount) {
        if (!enabled()) {
            return;
        }
        eventsCreated.increment();
        deliveriesCreated.increment(deliveryCount);
    }

    public void recordClaim(DeliveryClaimDisposition disposition) {
        if (enabled()) {
            counter("webhook.delivery.claims", OUTCOME_TAG, disposition.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
    }

    public void recordWorkerOutcome(DeliveryWorkerDisposition disposition) {
        if (enabled()) {
            counter("webhook.delivery.outcomes", OUTCOME_TAG, disposition.name().toLowerCase(Locale.ROOT))
                    .increment();
        }
    }

    public void recordRetry(DeliveryStatus status) {
        if (!enabled()) {
            return;
        }
        String outcome = status.name().toLowerCase(Locale.ROOT);
        counter("webhook.delivery.retries", OUTCOME_TAG, outcome).increment();
        if (status == DeliveryStatus.DEAD) {
            deliveryDead.increment();
        }
    }

    public Timer.Sample startHttpTimer() {
        return enabled() ? Timer.start(registry) : null;
    }

    public void recordHttpResult(Timer.Sample sample, DeliveryHttpResult result) {
        if (!enabled() || sample == null) {
            return;
        }
        String resultTag = result.hasHttpStatus() ? "http" : "transport";
        String statusClass = result.hasHttpStatus()
                ? statusClass(result.httpStatus())
                : "transport";
        sample.stop(timer(resultTag, statusClass));
    }

    public void recordKafkaCommand(String result) {
        if (enabled()) {
            counter("webhook.kafka.commands", RESULT_TAG, boundedResult(result)).increment();
        }
    }

    public void recordOutboxPublish(String result) {
        if (enabled()) {
            counter("webhook.outbox.publishes", RESULT_TAG, boundedResult(result)).increment();
        }
    }

    private Counter counter(String name, String tag, String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }

    private Timer timer(String result, String statusClass) {
        return Timer.builder("webhook.delivery.http.duration")
                .tag(RESULT_TAG, result)
                .tag(STATUS_CLASS_TAG, statusClass)
                .publishPercentileHistogram()
                .register(registry);
    }

    private boolean enabled() {
        return registry != null;
    }

    private static String boundedResult(String result) {
        return switch (result) {
            case "processed", "busy", "discarded", "published", "retry" -> result;
            default -> "other";
        };
    }

    private static String statusClass(int status) {
        if (status >= 200 && status <= 299) {
            return "2xx";
        }
        if (status >= 400 && status <= 499) {
            return "4xx";
        }
        if (status >= 500 && status <= 599) {
            return "5xx";
        }
        return "other";
    }

    private static double queryCount(JdbcTemplate jdbcTemplate, String sql) {
        try {
            Number count = jdbcTemplate.queryForObject(sql, Number.class);
            return count == null ? 0 : count.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
