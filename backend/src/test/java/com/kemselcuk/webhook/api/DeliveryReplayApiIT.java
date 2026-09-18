package com.kemselcuk.webhook.api;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webhook.outbox.publisher.enabled=false",
                "webhook.delivery.retry.scheduler.enabled=false"
        }
)
class DeliveryReplayApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_delivery_replay_api_test")
            .withUsername("webhook_delivery_replay_api_test")
            .withPassword("webhook_delivery_replay_api_test");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void clearDatabase() {
        outboxEventRepository.deleteAllInBatch();
        deliveryAttemptRepository.deleteAllInBatch();
        deliveryRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    @Test
    void replayReturnsAcceptedResponseAndPersistsPendingStateAndOutbox() throws Exception {
        Delivery delivery = seedDelivery("DEAD", true, 3, 3);

        ResponseEntity<JsonNode> response = replay(delivery.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("deliveryId").asText()).isEqualTo(delivery.getId().toString());
        assertThat(response.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(response.getBody().get("attemptCount").asInt()).isEqualTo(3);
        assertThat(response.getBody().get("currentRunAttemptCount").asInt()).isZero();

        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT status, next_retry_at, attempt_count, run_attempt_count, claim_token, claimed_at "
                        + "FROM deliveries WHERE id = ?",
                delivery.getId()
        );
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("next_retry_at")).isNull();
        assertThat(state.get("attempt_count")).isEqualTo(3);
        assertThat(state.get("run_attempt_count")).isEqualTo(0);
        assertThat(state.get("claim_token")).isNull();
        assertThat(state.get("claimed_at")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE delivery_id = ?",
                Integer.class,
                delivery.getId()
        )).isEqualTo(1);
    }

    @Test
    void mapsReplayEligibilityFailuresToBoundedProblemDetails() {
        ResponseEntity<JsonNode> missing = replay(UUID.randomUUID());
        assertProblem(missing, HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND");

        Delivery ineligible = seedDelivery("PENDING", true, 0, 0);
        assertProblem(replay(ineligible.getId()), HttpStatus.CONFLICT, "DELIVERY_NOT_REPLAYABLE");

        Delivery disabled = seedDelivery("DEAD", false, 2, 2);
        assertProblem(replay(disabled.getId()), HttpStatus.CONFLICT, "ENDPOINT_DISABLED");

        assertThat(outboxEventRepository.count()).isZero();
    }

    private ResponseEntity<JsonNode> replay(UUID deliveryId) {
        return restTemplate.exchange(
                url("/api/deliveries/" + deliveryId + "/replay"),
                HttpMethod.POST,
                HttpEntity.EMPTY,
                JsonNode.class
        );
    }

    private void assertProblem(
            ResponseEntity<JsonNode> response,
            HttpStatus status,
            String code
    ) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo(code);
        assertThat(response.getBody().get("detail").asText()).doesNotContain("https://");
    }

    private Delivery seedDelivery(
            String status,
            boolean enabled,
            int attemptCount,
            int runAttemptCount
    ) {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create("Replay API-" + UUID.randomUUID(), "https://replay-api.example.test/hooks")
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

    private JsonNode payload() {
        try {
            return objectMapper.readTree("{\"orderId\":\"replay-api-123\"}");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
