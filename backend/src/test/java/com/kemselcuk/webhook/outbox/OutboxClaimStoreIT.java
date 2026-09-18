package com.kemselcuk.webhook.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.Event;
import com.kemselcuk.webhook.domain.OutboxEvent;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        properties = "webhook.outbox.publisher.enabled=false"
)
class OutboxClaimStoreIT {

    private static final Instant TEST_AVAILABLE_AT = Instant.parse("2026-09-18T09:59:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_outbox_claim_test")
            .withUsername("webhook_outbox_claim_test")
            .withPassword("webhook_outbox_claim_test");

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
    private OutboxClaimStore claimStore;

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
    void concurrentClaimersReceiveDisjointBatches() throws Exception {
        seedOutbox(12);
        Instant now = Instant.parse("2026-09-18T10:00:00Z");
        CountDownLatch start = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);

        Callable<List<OutboxClaim>> claimWork = () -> {
            start.await(10, TimeUnit.SECONDS);
            return claimStore.claimBatch(6, now, Duration.ofMinutes(5));
        };
        Future<List<OutboxClaim>> firstFuture = executor.submit(claimWork);
        Future<List<OutboxClaim>> secondFuture = executor.submit(claimWork);
        start.countDown();

        List<OutboxClaim> first = firstFuture.get(10, TimeUnit.SECONDS);
        List<OutboxClaim> second = secondFuture.get(10, TimeUnit.SECONDS);
        Set<UUID> firstIds = first.stream().map(OutboxClaim::id).collect(java.util.stream.Collectors.toSet());
        Set<UUID> secondIds = second.stream().map(OutboxClaim::id).collect(java.util.stream.Collectors.toSet());
        Set<UUID> claimedIds = new HashSet<>(firstIds);
        claimedIds.addAll(secondIds);

        assertThat(first).hasSize(6);
        assertThat(second).hasSize(6);
        assertThat(firstIds).doesNotContainAnyElementsOf(secondIds);
        assertThat(claimedIds).hasSize(12);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'CLAIMED'", Integer.class
        )).isEqualTo(12);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT MIN(publish_attempts) FROM outbox_events", Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT MAX(publish_attempts) FROM outbox_events", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void staleClaimCanBeReclaimedButOldTokenCannotChangeNewLease() throws Exception {
        OutboxEvent outboxEvent = seedOutbox(1).getFirst();
        Instant firstClaimAt = Instant.parse("2026-09-18T10:00:00Z");
        Instant staleClaimAt = firstClaimAt.plus(Duration.ofMinutes(6));
        OutboxClaim firstClaim = claimStore.claimBatch(
                1, firstClaimAt, Duration.ofMinutes(5)
        ).getFirst();
        OutboxClaim replacementClaim = claimStore.claimBatch(
                1, staleClaimAt, Duration.ofMinutes(5)
        ).getFirst();

        assertThat(replacementClaim.id()).isEqualTo(outboxEvent.getId());
        assertThat(replacementClaim.claimToken()).isNotEqualTo(firstClaim.claimToken());
        assertThat(claimStore.markPublished(firstClaim, staleClaimAt)).isFalse();
        assertThat(claimStore.releaseForRetry(
                firstClaim,
                staleClaimAt,
                staleClaimAt.plusSeconds(30),
                "TIMEOUT"
        )).isFalse();

        Instant retryAt = staleClaimAt.plusSeconds(30);
        assertThat(claimStore.releaseForRetry(
                replacementClaim,
                staleClaimAt,
                retryAt,
                "TIMEOUT"
        )).isTrue();
        var released = jdbcTemplate.queryForMap("""
                SELECT status, claim_token, claimed_at, available_at, updated_at, last_error, publish_attempts
                FROM outbox_events
                WHERE id = ?
                """, outboxEvent.getId());
        assertThat(released.get("status")).isEqualTo("PENDING");
        assertThat(released.get("claim_token")).isNull();
        assertThat(released.get("claimed_at")).isNull();
        assertThat(((java.sql.Timestamp) released.get("available_at")).toInstant()).isEqualTo(retryAt);
        assertThat(((java.sql.Timestamp) released.get("updated_at")).toInstant()).isEqualTo(staleClaimAt);
        assertThat(released.get("last_error")).isEqualTo("TIMEOUT");
        assertThat(released.get("publish_attempts")).isEqualTo(2);

        OutboxClaim finalClaim = claimStore.claimBatch(
                1, retryAt, Duration.ofMinutes(5)
        ).getFirst();
        assertThat(claimStore.markPublished(finalClaim, retryAt.plusSeconds(1))).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, outboxEvent.getId()
        )).isEqualTo("PUBLISHED");
    }

    @Test
    void publisherFailureIsDurablyRequeuedAndLaterSuccessPublishes() throws Exception {
        OutboxEvent outboxEvent = seedOutbox(1).getFirst();
        Instant firstCycleAt = Instant.parse("2026-09-18T10:00:00Z");
        Duration retryDelay = Duration.ofSeconds(30);
        Instant retryAt = firstCycleAt.plus(retryDelay);
        MutableClock clock = new MutableClock(firstCycleAt);
        ControllableCommandSender sender = new ControllableCommandSender();
        OutboxPublisherProperties properties = new OutboxPublisherProperties();
        properties.setBatchSize(1);
        properties.setClaimTimeout(Duration.ofMinutes(5));
        properties.setSendTimeout(Duration.ofSeconds(2));
        properties.setRetryDelay(retryDelay);
        OutboxPublisher publisher = new OutboxPublisher(
                claimStore, sender, properties, objectMapper, clock
        );

        assertThat(publisher.publishSingleCycle()).isZero();
        Map<String, Object> requeued = outboxState(outboxEvent.getId());
        assertThat(requeued.get("status")).isEqualTo("PENDING");
        assertThat(requeued.get("claim_token")).isNull();
        assertThat(requeued.get("claimed_at")).isNull();
        assertThat(asInstant(requeued.get("available_at"))).isEqualTo(retryAt);
        assertThat(asInstant(requeued.get("updated_at"))).isEqualTo(firstCycleAt);
        assertThat(requeued.get("last_error")).isEqualTo("TIMEOUT");
        assertThat(requeued.get("publish_attempts")).isEqualTo(1);

        clock.set(retryAt);
        assertThat(publisher.publishSingleCycle()).isEqualTo(1);
        Map<String, Object> published = outboxState(outboxEvent.getId());
        assertThat(published.get("status")).isEqualTo("PUBLISHED");
        assertThat(published.get("claim_token")).isNull();
        assertThat(published.get("claimed_at")).isNull();
        assertThat(asInstant(published.get("published_at"))).isEqualTo(retryAt);
        assertThat(published.get("last_error")).isNull();
        assertThat(published.get("publish_attempts")).isEqualTo(2);
        assertThat(sender.sendCount).isEqualTo(2);
    }

    private Map<String, Object> outboxState(UUID id) {
        return jdbcTemplate.queryForMap("""
                SELECT status, claim_token, claimed_at, available_at, updated_at,
                       published_at, last_error, publish_attempts
                FROM outbox_events
                WHERE id = ?
                """, id);
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

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void set(Instant current) {
            this.current = current;
        }
    }

    private static final class ControllableCommandSender implements OutboxCommandSender {
        private int sendCount;

        @Override
        public void send(String topic, String key, String value, Duration timeout)
                throws TimeoutException {
            if (sendCount++ == 0) {
                throw new TimeoutException("simulated broker timeout");
            }
        }
    }

    private List<OutboxEvent> seedOutbox(int count) throws Exception {
        List<WebhookEndpoint> endpoints = IntStream.range(0, count)
                .mapToObj(index -> WebhookEndpoint.create(
                        "Orders-" + index,
                        "https://orders.example.test/hooks/" + index
                ))
                .toList();
        endpointRepository.saveAllAndFlush(endpoints);
        Event event = eventRepository.saveAndFlush(Event.create(
                "order.created",
                objectMapper.readTree("{\"orderId\":\"order-123\"}")
        ));
        List<Delivery> deliveries = endpoints.stream()
                .map(endpoint -> Delivery.create(event, endpoint))
                .toList();
        deliveryRepository.saveAllAndFlush(deliveries);
        List<OutboxEvent> outboxEvents = deliveries.stream()
                .map(OutboxEvent::forDelivery)
                .toList();
        List<OutboxEvent> savedOutboxEvents = outboxEventRepository.saveAllAndFlush(outboxEvents);
        savedOutboxEvents.forEach(outboxEvent -> jdbcTemplate.update(
                "UPDATE outbox_events SET available_at = ?, created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.from(TEST_AVAILABLE_AT),
                java.sql.Timestamp.from(TEST_AVAILABLE_AT),
                java.sql.Timestamp.from(TEST_AVAILABLE_AT),
                outboxEvent.getId()
        ));
        return savedOutboxEvents;
    }
}
