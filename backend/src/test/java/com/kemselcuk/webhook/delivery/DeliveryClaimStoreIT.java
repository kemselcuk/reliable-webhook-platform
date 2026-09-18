package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import com.kemselcuk.webhook.domain.Event;
import com.kemselcuk.webhook.domain.OutboxEvent;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryAttemptRepository;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import com.kemselcuk.webhook.security.SigningSecret;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        properties = "webhook.outbox.publisher.enabled=false"
)
class DeliveryClaimStoreIT {

    private static final Instant FIRST_CLAIM_AT = Instant.parse("2026-09-18T10:00:00Z");
    private static final Duration CLAIM_TIMEOUT = Duration.ofMinutes(5);
    private static final SigningSecret TEST_SECRET = SigningSecret.fromText("s".repeat(32));

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_delivery_claim_test")
            .withUsername("webhook_delivery_claim_test")
            .withPassword("webhook_delivery_claim_test");

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
    private DeliveryClaimStore claimStore;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    private ExecutorService executor;

    @BeforeEach
    void clearDatabase() {
        attemptRepository.deleteAllInBatch();
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
    void concurrentClaimersReceiveExactlyOneClaim() throws Exception {
        Delivery delivery = seedDelivery(true);
        CountDownLatch start = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);
        Callable<DeliveryClaimResult> claimWork = () -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        };

        Future<DeliveryClaimResult> firstFuture = executor.submit(claimWork);
        Future<DeliveryClaimResult> secondFuture = executor.submit(claimWork);
        start.countDown();
        DeliveryClaimResult first = firstFuture.get(10, TimeUnit.SECONDS);
        DeliveryClaimResult second = secondFuture.get(10, TimeUnit.SECONDS);

        List<DeliveryClaimResult> results = List.of(first, second);
        assertThat(results.stream().filter(result -> result.disposition()
                == DeliveryClaimDisposition.CLAIMED)).hasSize(1);
        assertThat(results.stream().filter(result -> result.disposition()
                == DeliveryClaimDisposition.BUSY)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM deliveries WHERE status = 'PROCESSING'", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void concurrentWorkersCannotActivelyProcessTheSameDelivery() throws Exception {
        Delivery delivery = seedDelivery(true);
        CountDownLatch httpEntered = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        AtomicInteger httpCalls = new AtomicInteger();
        DeliveryHttpClient blockingClient = work -> {
            httpCalls.incrementAndGet();
            httpEntered.countDown();
            try {
                if (!releaseHttp.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release HTTP exchange");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("HTTP exchange was interrupted", exception);
            }
            return DeliveryHttpResult.httpStatus(204);
        };
        DeliveryWorker firstWorker = worker(blockingClient);
        DeliveryWorker secondWorker = worker(blockingClient);
        executor = Executors.newFixedThreadPool(2);

        Future<DeliveryWorkerResult> first = executor.submit(() -> firstWorker.process(delivery.getId()));
        assertThat(httpEntered.await(10, TimeUnit.SECONDS)).isTrue();
        DeliveryWorkerResult secondResult = secondWorker.process(delivery.getId());

        assertThat(secondResult.disposition()).isEqualTo(DeliveryWorkerDisposition.BUSY);
        assertThat(httpCalls).hasValue(1);
        releaseHttp.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).disposition())
                .isEqualTo(DeliveryWorkerDisposition.SUCCESS);
        assertThat(deliveryRepository.findById(delivery.getId()).orElseThrow().getStatus())
                .isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .hasSize(1);
    }

    @Test
    void freshClaimIsBusyAndExpiredLeaseIsReclaimedWithAnotherToken() {
        Delivery delivery = seedDelivery(true);
        DeliveryClaimResult first = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        assertThat(first.disposition()).isEqualTo(DeliveryClaimDisposition.CLAIMED);
        assertThat(first.work().signingKeyId()).isEqualTo("v1");
        assertThat(first.work().signingSecret()).isEqualTo(TEST_SECRET);
        assertThat(first.work().signingSecret().toString()).doesNotContain("s".repeat(32));

        DeliveryClaimResult busy = claimStore.claim(
                delivery.getId(), FIRST_CLAIM_AT.plus(Duration.ofMinutes(1)), CLAIM_TIMEOUT
        );
        assertThat(busy.disposition()).isEqualTo(DeliveryClaimDisposition.BUSY);
        assertThat(busy.status()).isEqualTo(DeliveryStatus.PROCESSING);

        DeliveryClaimResult replacement = claimStore.claim(
                delivery.getId(), FIRST_CLAIM_AT.plus(Duration.ofMinutes(6)), CLAIM_TIMEOUT
        );
        assertThat(replacement.disposition()).isEqualTo(DeliveryClaimDisposition.CLAIMED);
        assertThat(replacement.work().claimToken())
                .isNotEqualTo(first.work().claimToken());
        assertThat(replacement.work().nextAttemptNumber()).isEqualTo(1);
        assertThat(replacement.work().currentRunAttemptNumber()).isEqualTo(1);
    }

    @Test
    void staleTokenCannotCompleteAfterReclaimAndCurrentTokenCompletesOnce() {
        Delivery delivery = seedDelivery(true);
        DeliveryClaimResult first = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        DeliveryClaimResult replacement = claimStore.claim(
                delivery.getId(), FIRST_CLAIM_AT.plus(Duration.ofMinutes(6)), CLAIM_TIMEOUT
        );
        Instant startedAt = FIRST_CLAIM_AT.plus(Duration.ofMinutes(6));
        Instant completedAt = startedAt.plusSeconds(2);

        assertThat(claimStore.completeSuccess(
                first.work(), 200, startedAt, completedAt
        )).isFalse();
        assertThat(claimStore.completeSuccess(
                replacement.work(), 202, startedAt, completedAt
        )).isTrue();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, claim_token, claimed_at, attempt_count FROM deliveries WHERE id = ?",
                delivery.getId()
        )).containsEntry("status", "SUCCESS")
                .containsEntry("claim_token", null)
                .containsEntry("claimed_at", null)
                .containsEntry("attempt_count", 1);
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .hasSize(1)
                .first()
                .satisfies(attempt -> {
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getOutcome()).isEqualTo(DeliveryAttemptOutcome.SUCCESS);
                    assertThat(attempt.getHttpStatus()).isEqualTo(202);
                    assertThat(attempt.getStartedAt()).isEqualTo(startedAt);
                    assertThat(attempt.getCompletedAt()).isEqualTo(completedAt);
                });
    }

    @Test
    void failedCompletionAtomicallyPersistsAttemptAndClearsLease() {
        Delivery delivery = seedDelivery(true);
        DeliveryClaimResult claim = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        Instant startedAt = FIRST_CLAIM_AT.plusSeconds(1);
        Instant completedAt = startedAt.plusSeconds(3);
        Instant nextRetryAt = completedAt.plusSeconds(60);

        assertThat(claimStore.completeFailure(
                claim.work(),
                DeliveryRetryDecision.scheduled(nextRetryAt, "UPSTREAM_TIMEOUT"),
                503,
                startedAt,
                completedAt
        )).isTrue();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, claim_token, claimed_at, attempt_count "
                        + "FROM deliveries WHERE id = ?",
                delivery.getId()
        )).containsEntry("status", "RETRY_SCHEDULED")
                .containsEntry("next_retry_at", java.sql.Timestamp.from(nextRetryAt))
                .containsEntry("claim_token", null)
                .containsEntry("claimed_at", null)
                .containsEntry("attempt_count", 1);
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .first()
                .satisfies(attempt -> {
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getOutcome())
                            .isEqualTo(DeliveryAttemptOutcome.RETRYABLE_FAILURE);
                    assertThat(attempt.getHttpStatus()).isEqualTo(503);
                    assertThat(attempt.getErrorCode()).isEqualTo("UPSTREAM_TIMEOUT");
                });
    }

    @Test
    void failedCompletionAllowsMissingHttpStatusForNetworkOutcome() {
        Delivery delivery = seedDelivery(true);
        DeliveryClaimResult claim = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        Instant startedAt = FIRST_CLAIM_AT.plusSeconds(1);
        Instant completedAt = startedAt.plusSeconds(3);

        assertThat(claimStore.completeFailure(
                claim.work(),
                DeliveryRetryDecision.scheduled(completedAt.plusSeconds(60), "NETWORK_FAILURE"),
                null,
                startedAt,
                completedAt
        )).isTrue();
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .first()
                .satisfies(attempt -> assertThat(attempt.getHttpStatus()).isNull());
    }

    @Test
    void classifiedScheduledFailurePersistsRetryStateAndAttemptAtomically() {
        Delivery delivery = seedDelivery(true);
        DeliveryClaimResult claim = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        Instant startedAt = FIRST_CLAIM_AT.plusSeconds(1);
        Instant completedAt = startedAt.plusSeconds(3);
        Instant nextRetryAt = FIRST_CLAIM_AT.plusSeconds(60);

        assertThat(claimStore.completeFailure(
                claim.work(),
                DeliveryRetryDecision.scheduled(nextRetryAt, "HTTP_503"),
                503,
                startedAt,
                completedAt
        )).isTrue();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, claim_token, claimed_at, attempt_count, run_attempt_count "
                        + "FROM deliveries WHERE id = ?",
                delivery.getId()
        )).containsEntry("status", "RETRY_SCHEDULED")
                .containsEntry("next_retry_at", java.sql.Timestamp.from(nextRetryAt))
                .containsEntry("claim_token", null)
                .containsEntry("claimed_at", null)
                .containsEntry("attempt_count", 1)
                .containsEntry("run_attempt_count", 1);
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getAttemptNumber()).isEqualTo(1);
                    assertThat(attempt.getOutcome()).isEqualTo(DeliveryAttemptOutcome.RETRYABLE_FAILURE);
                    assertThat(attempt.getErrorCode()).isEqualTo("HTTP_503");
                });
    }

    @Test
    void classifiedDeadAndPermanentFailuresClearRetryTimeAndPersistCounts() {
        Delivery deadDelivery = seedDelivery(true, "OrdersDead");
        DeliveryClaimResult deadClaim = claimStore.claim(
                deadDelivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT
        );
        assertThat(claimStore.completeFailure(
                deadClaim.work(),
                DeliveryRetryDecision.dead("TIMEOUT"),
                null,
                FIRST_CLAIM_AT,
                FIRST_CLAIM_AT.plusSeconds(1)
        )).isTrue();

        Delivery permanentDelivery = seedDelivery(true, "OrdersPermanent");
        DeliveryClaimResult permanentClaim = claimStore.claim(
                permanentDelivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT
        );
        assertThat(claimStore.completeFailure(
                permanentClaim.work(),
                DeliveryRetryDecision.permanent("HTTP_404"),
                404,
                FIRST_CLAIM_AT,
                FIRST_CLAIM_AT.plusSeconds(1)
        )).isTrue();

        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, attempt_count, run_attempt_count "
                        + "FROM deliveries WHERE id = ?",
                deadDelivery.getId()
        )).containsEntry("status", "DEAD")
                .containsEntry("next_retry_at", null)
                .containsEntry("attempt_count", 1)
                .containsEntry("run_attempt_count", 1);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, attempt_count, run_attempt_count "
                        + "FROM deliveries WHERE id = ?",
                permanentDelivery.getId()
        )).containsEntry("status", "FAILED")
                .containsEntry("next_retry_at", null)
                .containsEntry("attempt_count", 1)
                .containsEntry("run_attempt_count", 1);
    }

    @Test
    void duplicateCommandAfterSuccessIsTerminalWithoutAnotherAttempt() {
        Delivery delivery = seedDelivery(true);
        DeliveryClaimResult claim = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        assertThat(claimStore.completeSuccess(
                claim.work(), 200, FIRST_CLAIM_AT, FIRST_CLAIM_AT.plusSeconds(1)
        )).isTrue();

        DeliveryClaimResult duplicate = claimStore.claim(
                delivery.getId(), FIRST_CLAIM_AT.plus(Duration.ofMinutes(1)), CLAIM_TIMEOUT
        );
        assertThat(duplicate.disposition()).isEqualTo(DeliveryClaimDisposition.TERMINAL);
        assertThat(duplicate.status()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .hasSize(1);
    }

    @Test
    void disabledEndpointIsReportedAndDeliveryRemainsPending() {
        Delivery delivery = seedDelivery(false);
        DeliveryClaimResult result = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);

        assertThat(result.disposition()).isEqualTo(DeliveryClaimDisposition.DISABLED);
        assertThat(result.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, claim_token, claimed_at FROM deliveries WHERE id = ?",
                delivery.getId()
        )).containsEntry("status", "PENDING")
                .containsEntry("claim_token", null)
                .containsEntry("claimed_at", null);
    }

    @Test
    void migrationRejectsIncoherentClaimState() {
        Delivery delivery = seedDelivery(true);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE deliveries SET status = 'PROCESSING' WHERE id = ?", delivery.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM deliveries WHERE id = ?", String.class, delivery.getId()
        )).isEqualTo("PENDING");
    }

    @Test
    void migrationRejectsIncoherentRetryStateAndRunCount() {
        Delivery delivery = seedDelivery(true);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE deliveries SET run_attempt_count = 1 WHERE id = ?", delivery.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE deliveries SET status = 'RETRY_SCHEDULED', next_retry_at = NULL WHERE id = ?",
                delivery.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE deliveries SET next_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.from(FIRST_CLAIM_AT),
                delivery.getId()
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimReturnsBoundedDispositionForMissingAndIneligibleCommands() {
        UUID missingId = UUID.randomUUID();
        assertThat(claimStore.claim(missingId, FIRST_CLAIM_AT, CLAIM_TIMEOUT).disposition())
                .isEqualTo(DeliveryClaimDisposition.NOT_FOUND);

        Delivery delivery = seedDelivery(true);
        jdbcTemplate.update(
                "UPDATE deliveries SET status = 'RETRY_SCHEDULED', next_retry_at = ? WHERE id = ?",
                java.sql.Timestamp.from(FIRST_CLAIM_AT),
                delivery.getId()
        );
        DeliveryClaimResult result = claimStore.claim(delivery.getId(), FIRST_CLAIM_AT, CLAIM_TIMEOUT);
        assertThat(result.disposition()).isEqualTo(DeliveryClaimDisposition.INELIGIBLE);
        assertThat(result.status()).isEqualTo(DeliveryStatus.RETRY_SCHEDULED);
    }

    private Delivery seedDelivery(boolean enabled) {
        return seedDelivery(enabled, "Orders");
    }

    private Delivery seedDelivery(boolean enabled, String name) {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create(name, "https://orders.example.test/hooks", TEST_SECRET)
        );
        if (!enabled) {
            endpoint.disable();
            endpointRepository.saveAndFlush(endpoint);
        }
        Event event = eventRepository.saveAndFlush(Event.create(
                "order.created",
                payload()
        ));
        Delivery delivery = deliveryRepository.saveAndFlush(Delivery.create(event, endpoint));
        outboxEventRepository.saveAndFlush(OutboxEvent.forDelivery(delivery));
        return delivery;
    }

    private JsonNode payload() {
        try {
            return objectMapper.readTree("{\"orderId\":\"order-123\",\"items\":[{\"sku\":\"sku-1\"}]}");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private DeliveryWorker worker(DeliveryHttpClient httpClient) {
        DeliveryWorkerProperties properties = new DeliveryWorkerProperties();
        properties.setClaimTimeout(CLAIM_TIMEOUT);
        return new DeliveryWorker(
                claimStore,
                httpClient,
                properties,
                new DeliveryRetryPolicy(new DeliveryRetryProperties(), () -> 0.5),
                Clock.fixed(FIRST_CLAIM_AT, ZoneOffset.UTC)
        );
    }
}
