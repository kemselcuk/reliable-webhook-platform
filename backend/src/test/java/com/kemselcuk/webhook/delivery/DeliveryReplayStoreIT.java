package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.Event;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryAttemptRepository;
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
        properties = {
                "webhook.outbox.publisher.enabled=false",
                "webhook.delivery.retry.scheduler.enabled=false"
        }
)
class DeliveryReplayStoreIT {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_delivery_replay_test")
            .withUsername("webhook_delivery_replay_test")
            .withPassword("webhook_delivery_replay_test");

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
    private DeliveryReplayStore replayStore;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private ExecutorService executor;

    @BeforeEach
    void clearDatabase() {
        outboxEventRepository.deleteAllInBatch();
        deliveryAttemptRepository.deleteAllInBatch();
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
    void replaysDeadDeliveryAtomicallyAndPreservesLifetimeAndAttemptHistory() throws Exception {
        Delivery delivery = seedDelivery("DEAD", true, 3, 3);
        insertAttempts(delivery.getId(), 3);

        DeliveryReplayResult result = replayStore.replay(delivery.getId(), NOW);

        assertThat(result.disposition()).isEqualTo(DeliveryReplayResult.Disposition.REPLAYED);
        assertThat(result.deliveryId()).isEqualTo(delivery.getId());
        assertThat(result.attemptCount()).isEqualTo(3);
        assertThat(result.currentRunAttemptCount()).isZero();
        assertReplayState(delivery.getId(), 3, 0);
        assertThat(attemptCount(delivery.getId())).isEqualTo(3);
        assertOutbox(delivery.getId());
    }

    @Test
    void replaysFailedDeliveryAndResetsOnlyCurrentRunCounter() throws Exception {
        Delivery delivery = seedDelivery("FAILED", true, 5, 2);

        DeliveryReplayResult result = replayStore.replay(delivery.getId(), NOW);

        assertThat(result.disposition()).isEqualTo(DeliveryReplayResult.Disposition.REPLAYED);
        assertThat(result.attemptCount()).isEqualTo(5);
        assertReplayState(delivery.getId(), 5, 0);
        assertOutbox(delivery.getId());
    }

    @Test
    void reportsMissingDisabledAndIneligibleDeliveriesWithoutWritingOutbox() {
        UUID missingId = UUID.randomUUID();
        assertThat(replayStore.replay(missingId, NOW).disposition())
                .isEqualTo(DeliveryReplayResult.Disposition.NOT_FOUND);

        Delivery disabled = seedDelivery("DEAD", false, 2, 2);
        assertThat(replayStore.replay(disabled.getId(), NOW).disposition())
                .isEqualTo(DeliveryReplayResult.Disposition.DISABLED);

        Delivery pending = seedDelivery("PENDING", true, 0, 0);
        assertThat(replayStore.replay(pending.getId(), NOW).disposition())
                .isEqualTo(DeliveryReplayResult.Disposition.INELIGIBLE);

        assertThat(outboxEventRepository.count()).isZero();
        assertThat(deliveryState(disabled.getId()).get("status")).isEqualTo("DEAD");
        assertThat(deliveryState(pending.getId()).get("status")).isEqualTo("PENDING");
    }

    @Test
    void concurrentReplayTransitionsOneDeliveryAndCreatesOneOutboxCommand() throws Exception {
        Delivery delivery = seedDelivery("FAILED", true, 4, 4);
        CountDownLatch start = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);

        List<Future<DeliveryReplayResult>> futures = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            futures.add(executor.submit(() -> {
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return replayStore.replay(delivery.getId(), NOW);
            }));
        }
        start.countDown();

        List<DeliveryReplayResult> results = List.of(
                futures.get(0).get(10, TimeUnit.SECONDS),
                futures.get(1).get(10, TimeUnit.SECONDS)
        );
        assertThat(results.stream()
                .filter(result -> result.disposition() == DeliveryReplayResult.Disposition.REPLAYED)
                .count()).isEqualTo(1);
        assertThat(results.stream()
                .filter(result -> result.disposition() == DeliveryReplayResult.Disposition.INELIGIBLE)
                .count()).isEqualTo(1);
        assertReplayState(delivery.getId(), 4, 0);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                Integer.class,
                delivery.getId()
        )).isEqualTo(1);
    }

    private Delivery seedDelivery(
            String status,
            boolean enabled,
            int attemptCount,
            int runAttemptCount
    ) {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create("Replay-" + UUID.randomUUID(), "https://replay.example.test/hooks")
        );
        if (!enabled) {
            endpoint.disable();
            endpointRepository.saveAndFlush(endpoint);
        }
        Event event = eventRepository.saveAndFlush(Event.create("order.created", payload()));
        Delivery delivery = deliveryRepository.saveAndFlush(Delivery.create(event, endpoint));
        jdbcTemplate.update(
                "UPDATE deliveries SET status = ?, attempt_count = ?, run_attempt_count = ? WHERE id = ?",
                status,
                attemptCount,
                runAttemptCount,
                delivery.getId()
        );
        return delivery;
    }

    private void insertAttempts(UUID deliveryId, int count) {
        for (int number = 1; number <= count; number++) {
            jdbcTemplate.update(
                    """
                    INSERT INTO delivery_attempts (
                        id, delivery_id, attempt_number, outcome, http_status,
                        error_code, started_at, completed_at
                    ) VALUES (?, ?, ?, 'RETRYABLE_FAILURE', 500, 'HTTP_500', ?, ?)
                    """,
                    UUID.randomUUID(),
                    deliveryId,
                    number,
                    java.sql.Timestamp.from(NOW.minusSeconds(2)),
                    java.sql.Timestamp.from(NOW.minusSeconds(1))
            );
        }
    }

    private void assertReplayState(UUID deliveryId, int attemptCount, int runAttemptCount) {
        Map<String, Object> state = deliveryState(deliveryId);
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("next_retry_at")).isNull();
        assertThat(state.get("attempt_count")).isEqualTo(attemptCount);
        assertThat(state.get("run_attempt_count")).isEqualTo(runAttemptCount);
        assertThat(state.get("claim_token")).isNull();
        assertThat(state.get("claimed_at")).isNull();
    }

    private void assertOutbox(UUID deliveryId) throws Exception {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                Integer.class,
                deliveryId
        )).isEqualTo(1);
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "SELECT event_type, payload::text AS payload, status, available_at, created_at, updated_at "
                        + "FROM outbox_events WHERE delivery_id = ?",
                deliveryId
        );
        assertThat(outbox.get("event_type")).isEqualTo("DELIVERY_REQUESTED");
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(asInstant(outbox.get("available_at"))).isEqualTo(NOW);
        assertThat(asInstant(outbox.get("created_at"))).isEqualTo(NOW);
        assertThat(asInstant(outbox.get("updated_at"))).isEqualTo(NOW);
        JsonNode payload = objectMapper.readTree((String) outbox.get("payload"));
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("deliveryId").asText()).isEqualTo(deliveryId.toString());
    }

    private Map<String, Object> deliveryState(UUID deliveryId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, attempt_count, run_attempt_count, claim_token, claimed_at "
                        + "FROM deliveries WHERE id = ?",
                deliveryId
        );
    }

    private int attemptCount(UUID deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM delivery_attempts WHERE delivery_id = ?",
                Integer.class,
                deliveryId
        );
    }

    private JsonNode payload() {
        try {
            return objectMapper.readTree("{\"orderId\":\"replay-123\"}");
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
