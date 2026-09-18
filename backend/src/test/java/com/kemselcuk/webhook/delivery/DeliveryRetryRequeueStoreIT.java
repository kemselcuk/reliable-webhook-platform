package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.Event;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        properties = "webhook.outbox.publisher.enabled=false"
)
class DeliveryRetryRequeueStoreIT {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_retry_requeue_test")
            .withUsername("webhook_retry_requeue_test")
            .withPassword("webhook_retry_requeue_test");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryRetryRequeueStore requeueStore;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private ExecutorService executor;

    @BeforeEach
    void clearDatabase() {
        outboxEventRepository.deleteAllInBatch();
        deliveryRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    @AfterEach
    void stopExecutor() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void dueDeliveryBecomesPendingWithOneOutboxCommandAndPreservesCounters() throws Exception {
        Delivery delivery = seedRetry(true, NOW.minusSeconds(1), 4, 2);

        assertThat(requeueStore.requeueDue(50, NOW)).isEqualTo(1);

        Map<String, Object> state = deliveryState(delivery.getId());
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("next_retry_at")).isNull();
        assertThat(state.get("attempt_count")).isEqualTo(4);
        assertThat(state.get("run_attempt_count")).isEqualTo(2);
        assertThat(state.get("claim_token")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?", Integer.class,
                delivery.getId()
        )).isEqualTo(1);

        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT event_type, payload::text AS payload, status, available_at, created_at, updated_at "
                        + "FROM outbox_events WHERE delivery_id = ?",
                delivery.getId()
        );
        assertThat(outbox.get("event_type")).isEqualTo("DELIVERY_REQUESTED");
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(asInstant(outbox.get("available_at"))).isEqualTo(NOW);
        assertThat(asInstant(outbox.get("created_at"))).isEqualTo(NOW);
        assertThat(asInstant(outbox.get("updated_at"))).isEqualTo(NOW);
        JsonNode payload = objectMapper.readTree((String) outbox.get("payload"));
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("deliveryId").asText()).isEqualTo(delivery.getId().toString());
    }

    @Test
    void futureAndDisabledDueDeliveriesAreIgnored() {
        Delivery future = seedRetry(true, NOW.plusSeconds(1), 1, 1);
        Delivery disabled = seedRetry(false, NOW.minusSeconds(1), 1, 1);

        assertThat(requeueStore.requeueDue(50, NOW)).isZero();
        assertThat(deliveryState(future.getId()).get("status")).isEqualTo("RETRY_SCHEDULED");
        assertThat(deliveryState(disabled.getId()).get("status")).isEqualTo("RETRY_SCHEDULED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events", Integer.class
        )).isZero();
    }

    @Test
    void concurrentCyclesRequeueEachDueDeliveryExactlyOnce() throws Exception {
        List<Delivery> deliveries = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            deliveries.add(seedRetry(true, NOW.minusSeconds(index + 1L), 2, 2));
        }

        CountDownLatch start = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);
        Future<Integer> first = executor.submit(() -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return requeueStore.requeueDue(50, NOW);
        });
        Future<Integer> second = executor.submit(() -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return requeueStore.requeueDue(50, NOW);
        });
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE status = 'PENDING'", Integer.class
        )).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events", Integer.class
        )).isEqualTo(20);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE delivery_id IN "
                        + "(SELECT id FROM deliveries) ", Integer.class
        )).isEqualTo(20);
    }

    private Delivery seedRetry(boolean enabled, Instant retryAt, int attemptCount, int runAttemptCount) {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create("Retry-" + UUID.randomUUID(), "https://retry.example.test/hooks")
        );
        if (!enabled) {
            endpoint.disable();
            endpointRepository.saveAndFlush(endpoint);
        }
        Event event = eventRepository.saveAndFlush(Event.create(
                "order.created", payload()
        ));
        Delivery delivery = deliveryRepository.saveAndFlush(Delivery.create(event, endpoint));
        jdbcTemplate.update(
                "UPDATE deliveries SET status = 'RETRY_SCHEDULED', next_retry_at = ?, "
                        + "attempt_count = ?, run_attempt_count = ? WHERE id = ?",
                java.sql.Timestamp.from(retryAt), attemptCount, runAttemptCount, delivery.getId()
        );
        return delivery;
    }

    private Map<String, Object> deliveryState(UUID deliveryId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, attempt_count, run_attempt_count, claim_token "
                        + "FROM deliveries WHERE id = ?",
                deliveryId
        );
    }

    private JsonNode payload() {
        try {
            return objectMapper.readTree("{\"orderId\":\"retry-123\"}");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Instant asInstant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new AssertionError("Expected a PostgreSQL timestamp but got " + value);
    }
}
