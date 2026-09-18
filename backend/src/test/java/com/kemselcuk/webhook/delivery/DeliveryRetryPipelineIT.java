package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.Options;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.DeliveryAttempt;
import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import com.kemselcuk.webhook.domain.repository.DeliveryAttemptRepository;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webhook.outbox.publisher.enabled=true",
                "webhook.outbox.publisher.poll-interval=PT0.05S",
                "webhook.outbox.publisher.retry-delay=PT0.05S",
                "webhook.outbox.publisher.send-timeout=PT5S",
                "webhook.delivery.worker.enabled=true",
                "webhook.delivery.worker.claim-timeout=PT5M",
                "webhook.delivery.worker.connect-timeout=PT0.5S",
                "webhook.delivery.worker.response-timeout=PT0.25S",
                "webhook.delivery.worker.concurrency=3",
                "webhook.delivery.worker.busy-redelivery-delay=PT0.1S",
                "webhook.delivery.retry.max-attempts=3",
                "webhook.delivery.retry.initial-delay=PT0.1S",
                "webhook.delivery.retry.max-delay=PT1S",
                "webhook.delivery.retry.jitter-factor=0.0",
                "webhook.delivery.retry.scheduler.enabled=true",
                "webhook.delivery.retry.scheduler.poll-interval=PT0.05S",
                "webhook.delivery.retry.scheduler.batch-size=50",
                "spring.kafka.consumer.properties.max.poll.interval.ms=30000"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryRetryPipelineIT {

    private static final String GROUP_ID = "delivery-retry-pipeline-it-" + UUID.randomUUID();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_delivery_retry_pipeline_test")
            .withUsername("webhook_delivery_retry_pipeline_test")
            .withPassword("webhook_delivery_retry_pipeline_test");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.3.1")
    );

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(Options.DYNAMIC_PORT);
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("webhook.delivery.worker.group-id", () -> GROUP_ID);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        outboxEventRepository.deleteAllInBatch();
        deliveryAttemptRepository.deleteAllInBatch();
        deliveryRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    @Test
    void retries500TwiceThenSucceedsAndPublishesEachOutboxCommand() throws Exception {
        String path = uniquePath();
        stub500Then500Then204(path);
        UUID deliveryId = createDelivery(path);

        awaitDelivery(deliveryId, DeliveryStatus.SUCCESS, 3);

        wireMock.verify(3, postRequestedFor(urlEqualTo(path)));
        List<DeliveryAttempt> attempts = attempts(deliveryId);
        assertThat(attempts).extracting(DeliveryAttempt::getOutcome)
                .containsExactly(
                        DeliveryAttemptOutcome.RETRYABLE_FAILURE,
                        DeliveryAttemptOutcome.RETRYABLE_FAILURE,
                        DeliveryAttemptOutcome.SUCCESS
                );
        assertThat(attempts).extracting(DeliveryAttempt::getHttpStatus)
                .containsExactly(500, 500, 204);
        assertThat(delivery(deliveryId).getAttemptCount()).isEqualTo(3);
        assertThat(delivery(deliveryId).getRunAttemptCount()).isEqualTo(3);
        assertPublishedOutboxCount(deliveryId, 3);
    }

    @Test
    void retriesTransportTimeoutThenSucceeds() throws Exception {
        String path = uniquePath();
        stubTimeoutThen204(path);
        UUID deliveryId = createDelivery(path);

        awaitDelivery(deliveryId, DeliveryStatus.SUCCESS, 2);

        wireMock.verify(2, postRequestedFor(urlEqualTo(path)));
        List<DeliveryAttempt> attempts = attempts(deliveryId);
        assertThat(attempts).extracting(DeliveryAttempt::getOutcome)
                .containsExactly(DeliveryAttemptOutcome.RETRYABLE_FAILURE, DeliveryAttemptOutcome.SUCCESS);
        assertThat(attempts.getFirst().getHttpStatus()).isNull();
        assertThat(attempts.getFirst().getErrorCode()).isEqualTo("TIMEOUT");
        assertThat(attempts.get(1).getHttpStatus()).isEqualTo(204);
        assertPublishedOutboxCount(deliveryId, 2);
    }

    @Test
    void permanent400FailsOnceWithoutRetryOutbox() throws Exception {
        String path = uniquePath();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(400)));
        UUID deliveryId = createDelivery(path);

        awaitDelivery(deliveryId, DeliveryStatus.FAILED, 1);
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            wireMock.verify(1, postRequestedFor(urlEqualTo(path)));
            assertPublishedOutboxCount(deliveryId, 1);
            assertThat(attempts(deliveryId)).hasSize(1);
        });
        assertThat(attempts(deliveryId).getFirst().getOutcome())
                .isEqualTo(DeliveryAttemptOutcome.PERMANENT_FAILURE);
    }

    @Test
    void deadDeliveryCanBeReplayedAndSucceedsWithPreservedHistory() throws Exception {
        String path = uniquePath();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(500)));
        UUID deliveryId = createDelivery(path);

        awaitDelivery(deliveryId, DeliveryStatus.DEAD, 3);
        wireMock.verify(3, postRequestedFor(urlEqualTo(path)));
        assertPublishedOutboxCount(deliveryId, 3);

        wireMock.resetMappings();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(204)));

        ResponseEntity<JsonNode> replayResponse = restTemplate.exchange(
                url("/api/deliveries/" + deliveryId + "/replay"),
                HttpMethod.POST,
                HttpEntity.EMPTY,
                JsonNode.class
        );
        assertThat(replayResponse.getStatusCode().value()).isEqualTo(202);
        assertThat(replayResponse.getBody()).isNotNull();
        assertThat(replayResponse.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(replayResponse.getBody().get("attemptCount").asInt()).isEqualTo(3);
        assertThat(replayResponse.getBody().get("currentRunAttemptCount").asInt()).isZero();

        awaitDelivery(deliveryId, DeliveryStatus.SUCCESS, 4);
        wireMock.verify(4, postRequestedFor(urlEqualTo(path)));

        List<DeliveryAttempt> attempts = attempts(deliveryId);
        assertThat(attempts).hasSize(4);
        assertThat(attempts.subList(0, 3)).extracting(DeliveryAttempt::getHttpStatus)
                .containsExactly(500, 500, 500);
        assertThat(attempts.get(3).getOutcome()).isEqualTo(DeliveryAttemptOutcome.SUCCESS);
        assertThat(attempts.get(3).getHttpStatus()).isEqualTo(204);
        assertThat(delivery(deliveryId).getAttemptCount()).isEqualTo(4);
        assertThat(delivery(deliveryId).getRunAttemptCount()).isEqualTo(1);
        assertPublishedOutboxCount(deliveryId, 4);
    }

    private void stub500Then500Then204(String path) {
        String scenario = "retry-" + UUID.randomUUID();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("SECOND_FAILURE"));
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs("SECOND_FAILURE")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("SUCCESS"));
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs("SUCCESS")
                .willReturn(aResponse().withStatus(204)));
    }

    private void stubTimeoutThen204(String path) {
        String scenario = "timeout-" + UUID.randomUUID();
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFixedDelay(1_000).withStatus(500))
                .willSetStateTo("SUCCESS"));
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(path))
                .inScenario(scenario)
                .whenScenarioStateIs("SUCCESS")
                .willReturn(aResponse().withStatus(204)));
    }

    private UUID createDelivery(String path) throws Exception {
        String name = "Retry pipeline " + UUID.randomUUID();
        ResponseEntity<JsonNode> endpointResponse = post(
                "/api/webhook-endpoints",
                objectMapper.createObjectNode()
                        .put("name", name)
                        .put("url", wireMock.baseUrl() + path)
                        .put("secret", "ssssssssssssssssssssssssssssssss")
        );
        assertThat(endpointResponse.getStatusCode().value()).isEqualTo(201);
        UUID endpointId = UUID.fromString(endpointResponse.getBody().get("id").asText());

        ObjectNode event = objectMapper.createObjectNode();
        event.put("type", "order.created");
        event.set("payload", objectMapper.createObjectNode().put("orderId", UUID.randomUUID().toString()));
        event.set("endpointIds", objectMapper.createArrayNode().add(endpointId.toString()));
        ResponseEntity<JsonNode> eventResponse = post("/api/events", event);
        assertThat(eventResponse.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString(eventResponse.getBody().get("deliveryIds").get(0).asText());
    }

    private ResponseEntity<JsonNode> post(String path, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                url(path),
                HttpMethod.POST,
                new HttpEntity<>(body.toString(), headers),
                JsonNode.class
        );
    }

    private void awaitDelivery(UUID deliveryId, DeliveryStatus status, int expectedAttempts) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertThat(delivery(deliveryId).getStatus()).isEqualTo(status);
            assertThat(delivery(deliveryId).getAttemptCount()).isEqualTo(expectedAttempts);
            assertThat(attempts(deliveryId)).hasSize(expectedAttempts);
        });
    }

    private void assertPublishedOutboxCount(UUID deliveryId, int expectedCount) {
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                    Integer.class,
                    deliveryId
            )).isEqualTo(expectedCount);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ? AND status <> 'PUBLISHED'",
                    Integer.class,
                    deliveryId
            )).isZero();
        });
    }

    private com.kemselcuk.webhook.domain.Delivery delivery(UUID deliveryId) {
        return deliveryRepository.findById(deliveryId).orElseThrow();
    }

    private List<DeliveryAttempt> attempts(UUID deliveryId) {
        return deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId);
    }

    private String uniquePath() {
        return "/webhooks/retry/" + UUID.randomUUID();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
