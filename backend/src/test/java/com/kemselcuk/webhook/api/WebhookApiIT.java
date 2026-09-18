package com.kemselcuk.webhook.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.OutboxEvent;
import com.kemselcuk.webhook.domain.OutboxStatus;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.EventIdempotencyKeyRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import com.kemselcuk.webhook.event.api.CreateEventRequest;
import com.kemselcuk.webhook.event.api.EventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "webhook.outbox.publisher.enabled=false"
)
class WebhookApiIT {

    private static final String TEST_SECRET = "ssssssssssssssssssssssssssssssss";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_api_test")
            .withUsername("webhook_api_test")
            .withPassword("webhook_api_test");

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
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventIdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EventService eventService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ExecutorService executor;

    @BeforeEach
    void clearDatabase() {
        idempotencyKeyRepository.deleteAllInBatch();
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
    void createsAndListsEndpointsWithNormalizedUrlAndLocation() throws Exception {
        ResponseEntity<JsonNode> createResponse = post(
                "/api/webhook-endpoints",
                """
                {"name":"Orders","url":" https://example.test/a/../webhooks ","secret":"ssssssssssssssssssssssssssssssss"}
                """
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getHeaders().getLocation()).isNotNull();
        assertThat(createResponse.getHeaders().getLocation().getPath())
                .startsWith("/api/webhook-endpoints/");
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().get("name").asText()).isEqualTo("Orders");
        assertThat(createResponse.getBody().get("url").asText())
                .isEqualTo("https://example.test/webhooks");
        assertThat(createResponse.getBody().get("enabled").asBoolean()).isTrue();

        ResponseEntity<JsonNode> paymentsResponse = post("/api/webhook-endpoints", """
                {"name":"Payments","url":"http://payments.test/hooks","secret":"ssssssssssssssssssssssssssssssss"}
                """);
        assertThat(paymentsResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> listResponse = restTemplate.exchange(
                url("/api/webhook-endpoints?page=0&size=20"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                JsonNode.class
        );

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = listResponse.getBody();
        assertThat(page).isNotNull();
        assertThat(page.get("items")).hasSize(2);
        assertThat(page.get("page").asInt()).isZero();
        assertThat(page.get("size").asInt()).isEqualTo(20);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.get("totalPages").asInt()).isEqualTo(1);

        String expectedFirstId = List.of(createResponse.getBody(), paymentsResponse.getBody()).stream()
                .max(Comparator
                        .comparing((JsonNode endpoint) -> Instant.parse(endpoint.get("createdAt").asText()))
                        .thenComparing(endpoint -> endpoint.get("id").asText()))
                .orElseThrow()
                .get("id")
                .asText();
        assertThat(page.get("items").get(0).get("id").asText()).isEqualTo(expectedFirstId);
    }

    @Test
    void capsEndpointListSizeAtOneHundred() throws Exception {
        post("/api/webhook-endpoints", """
                {"name":"Orders","url":"https://orders.test/hooks","secret":"ssssssssssssssssssssssssssssssss"}
                """);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                url("/api/webhook-endpoints?page=0&size=200"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                JsonNode.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("size").asInt()).isEqualTo(100);
    }

    @Test
    void rejectsDuplicateEndpointNameWithConflict() throws Exception {
        post("/api/webhook-endpoints", """
                {"name":"Orders","url":"https://orders.test/hooks","secret":"ssssssssssssssssssssssssssssssss"}
                """);

        ResponseEntity<JsonNode> response = post(
                "/api/webhook-endpoints",
                """
                {"name":"Orders","url":"https://another.test/hooks","secret":"ssssssssssssssssssssssssssssssss"}
                """
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("ENDPOINT_NAME_CONFLICT");
        assertThat(endpointRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidEndpointUrl() throws Exception {
        ResponseEntity<JsonNode> response = post(
                "/api/webhook-endpoints",
                """
                {"name":"Orders","url":"ftp://example.test/hooks#fragment","secret":"ssssssssssssssssssssssssssssssss"}
                """
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(endpointRepository.count()).isZero();
    }

    @Test
    void storesExactSecretBytesButNeverExposesSecretInEndpointResponses() throws Exception {
        String secret = "  " + "é".repeat(16) + "  ";
        ResponseEntity<JsonNode> response = post(
                "/api/webhook-endpoints",
                objectMapper.writeValueAsString(Map.of(
                        "name", "Secret Orders",
                        "url", "https://orders.test/hooks",
                        "secret", secret
                ))
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().toString()).doesNotContain(secret);
        UUID endpointId = UUID.fromString(response.getBody().get("id").asText());
        byte[] stored = jdbcTemplate.queryForObject(
                "SELECT secret_material FROM webhook_endpoint_secrets WHERE webhook_endpoint_id = ?",
                byte[].class,
                endpointId
        );
        assertThat(stored).isEqualTo(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<JsonNode> list = restTemplate.exchange(
                url("/api/webhook-endpoints?page=0&size=20"),
                HttpMethod.GET,
                HttpEntity.EMPTY,
                JsonNode.class
        );
        assertThat(list.getBody()).isNotNull();
        assertThat(list.getBody().toString()).doesNotContain(secret);
    }

    @Test
    void rejectsSecretsOutsideUtf8ByteBoundsWithoutReturningMaterial() throws Exception {
        String oversized = "é".repeat(257);
        ResponseEntity<JsonNode> response = post(
                "/api/webhook-endpoints",
                objectMapper.writeValueAsString(Map.of(
                        "name", "Oversized Secret",
                        "url", "https://orders.test/hooks",
                        "secret", oversized
                ))
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().toString()).doesNotContain(oversized);
        assertThat(endpointRepository.count()).isZero();
    }

    @Test
    void rejectsDuplicateEventEndpointIdsWithoutCreatingEventOrDeliveries() throws Exception {
        JsonNode orders = createEndpoint("Orders", "https://orders.test/hooks");

        ResponseEntity<JsonNode> response = post(
                "/api/events",
                """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s","%s"]}
                """.formatted(orders.get("id").asText(), orders.get("id").asText())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(eventRepository.count()).isZero();
        assertThat(deliveryRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void createsOnePendingDeliveryForEachSelectedEndpoint() throws Exception {
        JsonNode orders = createEndpoint("Orders", "https://orders.test/hooks");
        JsonNode payments = createEndpoint("Payments", "https://payments.test/hooks");

        ResponseEntity<JsonNode> response = post(
                "/api/events",
                """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s","%s"]}
                """.formatted(orders.get("id").asText(), payments.get("id").asText())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("type").asText()).isEqualTo("order.created");
        assertThat(response.getBody().get("payload").get("orderId").asText())
                .isEqualTo("order-123");
        assertThat(response.getBody().get("deliveryIds")).hasSize(2);
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(2);
        assertThat(deliveryRepository.findAll()).extracting(delivery -> delivery.getStatus().name())
                .containsOnly("PENDING");
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        Set<String> deliveryIds = response.getBody().get("deliveryIds").valueStream()
                .map(JsonNode::asText)
                .collect(Collectors.toSet());
        Set<String> outboxDeliveryIds = outboxEventRepository.findAll().stream()
                .map(outboxEvent -> outboxEvent.getPayload().get("deliveryId").asText())
                .collect(Collectors.toSet());
        assertThat(outboxDeliveryIds).containsExactlyInAnyOrderElementsOf(deliveryIds);
        assertThat(outboxEventRepository.findAll())
                .allSatisfy(outboxEvent -> {
                    assertThat(outboxEvent.getEventType())
                            .isEqualTo(OutboxEvent.DELIVERY_REQUESTED_EVENT_TYPE);
                    assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
                    assertThat(outboxEvent.getPayload()).hasSize(2);
                    assertThat(outboxEvent.getPayload().get("version").asInt()).isEqualTo(1);
                });
    }

    @Test
    void reusesIdempotencyKeyForCanonicalEquivalentRequestWithoutCreatingDuplicates() throws Exception {
        JsonNode orders = createEndpoint("Orders", "https://orders.test/hooks");
        JsonNode payments = createEndpoint("Payments", "https://payments.test/hooks");
        String ordersId = orders.get("id").asText();
        String paymentsId = payments.get("id").asText();

        ResponseEntity<JsonNode> first = postWithKey(
                "/api/events",
                "event-submit-1",
                """
                {"type":" order.created ","payload":{"b":2.0,"a":1},"endpointIds":["%s","%s"]}
                """.formatted(ordersId, paymentsId)
        );
        ResponseEntity<JsonNode> second = postWithKey(
                "/api/events",
                "event-submit-1",
                """
                {"endpointIds":["%s","%s"],"payload":{"a":1.0,"b":2},"type":"order.created"}
                """.formatted(paymentsId, ordersId)
        );

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getLocation()).isEqualTo(first.getHeaders().getLocation());
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(2);
        assertThat(outboxEventRepository.count()).isEqualTo(2);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentRequestAndLeavesStoredRowsUntouched() throws Exception {
        JsonNode orders = createEndpoint("Orders", "https://orders.test/hooks");
        String endpointId = orders.get("id").asText();
        String key = "event-submit-conflict";

        ResponseEntity<JsonNode> first = postWithKey(
                "/api/events",
                key,
                """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s"]}
                """.formatted(endpointId)
        );
        ResponseEntity<JsonNode> second = postWithKey(
                "/api/events",
                key,
                """
                {"type":"order.created","payload":{"orderId":"order-456"},"endpointIds":["%s"]}
                """.formatted(endpointId)
        );

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalIdempotencyKeysCreateOneEventAndReturnTheSameResponse() throws Exception {
        JsonNode endpoint = createEndpoint("Orders", "https://orders.test/hooks");
        String endpointId = endpoint.get("id").asText();
        String body = """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s"]}
                """.formatted(endpointId);
        String key = "concurrent-identical-key";

        List<ResponseEntity<JsonNode>> responses = concurrentlySubmit(
                key, body, key, body
        );

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getHeaders().getLocation()).isEqualTo(
                    responses.getFirst().getHeaders().getLocation()
            );
            assertThat(response.getBody()).isEqualTo(responses.getFirst().getBody());
        });
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentDifferentPayloadsUseOneWinnerAndReturnAConflictForTheLoser() throws Exception {
        JsonNode endpoint = createEndpoint("Orders", "https://orders.test/hooks");
        String endpointId = endpoint.get("id").asText();
        String firstBody = """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s"]}
                """.formatted(endpointId);
        String secondBody = """
                {"type":"order.created","payload":{"orderId":"order-456"},"endpointIds":["%s"]}
                """.formatted(endpointId);
        String key = "concurrent-conflicting-key";

        List<ResponseEntity<JsonNode>> responses = concurrentlySubmit(
                key, firstBody, key, secondBody
        );

        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(responses.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .findFirst()
                .orElseThrow()
                .getBody()
                .get("code")
                .asText()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void outboxInsertFailureRollsBackEventAndDeliveries() throws Exception {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create("Orders", "https://orders.test/hooks")
        );
        installFailingOutboxInsertTrigger();

        try {
            assertThatThrownBy(() -> eventService.create(new CreateEventRequest(
                    "order.created",
                    objectMapper.readTree("{\"orderId\":\"order-123\"}"),
                    List.of(endpoint.getId())
            ))).isInstanceOf(DataIntegrityViolationException.class);

            assertThat(eventRepository.count()).isZero();
            assertThat(deliveryRepository.count()).isZero();
            assertThat(outboxEventRepository.count()).isZero();
        } finally {
            removeFailingOutboxInsertTrigger();
        }
    }

    private void installFailingOutboxInsertTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_fail_outbox_insert()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    RAISE EXCEPTION 'forced outbox insert failure' USING ERRCODE = '23514';
                END;
                $$
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER test_fail_outbox_insert_trigger
                BEFORE INSERT ON outbox_events
                FOR EACH ROW EXECUTE FUNCTION test_fail_outbox_insert()
                """);
    }

    private void removeFailingOutboxInsertTrigger() {
        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS test_fail_outbox_insert_trigger ON outbox_events
                """);
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_fail_outbox_insert()");
    }

    @Test
    void missingEndpointRollsBackEventAndDeliveries() throws Exception {
        JsonNode orders = createEndpoint("Orders", "https://orders.test/hooks");

        ResponseEntity<JsonNode> response = post(
                "/api/events",
                """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s","%s"]}
                """.formatted(orders.get("id").asText(), UUID.randomUUID())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("ENDPOINT_NOT_FOUND");
        assertThat(eventRepository.count()).isZero();
        assertThat(deliveryRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void disabledEndpointRollsBackEventAndDeliveries() throws Exception {
        JsonNode orders = createEndpoint("Orders", "https://orders.test/hooks");
        WebhookEndpoint endpoint = endpointRepository.findById(
                UUID.fromString(orders.get("id").asText())
        ).orElseThrow();
        endpoint.disable();
        endpointRepository.saveAndFlush(endpoint);

        ResponseEntity<JsonNode> response = post(
                "/api/events",
                """
                {"type":"order.created","payload":{"orderId":"order-123"},"endpointIds":["%s"]}
                """.formatted(orders.get("id").asText())
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("ENDPOINT_DISABLED");
        assertThat(eventRepository.count()).isZero();
        assertThat(deliveryRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    void rejectsInvalidEventRequestAndMalformedJsonWithProblemDetails() throws Exception {
        ResponseEntity<JsonNode> validationResponse = post(
                "/api/events",
                """
                {"type":"","payload":null,"endpointIds":[]}
                """
        );

        assertThat(validationResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(validationResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(validationResponse.getBody()).isNotNull();
        assertThat(validationResponse.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(validationResponse.getBody().get("fieldErrors")).isNotEmpty();

        ResponseEntity<JsonNode> malformedResponse = post(
                "/api/webhook-endpoints",
                "{\"name\":"
        );

        assertThat(malformedResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformedResponse.getBody()).isNotNull();
        assertThat(malformedResponse.getBody().get("code").asText()).isEqualTo("MALFORMED_JSON");
    }

    private JsonNode createEndpoint(String name, String endpointUrl) throws Exception {
        ResponseEntity<JsonNode> response = post(
                "/api/webhook-endpoints",
                objectMapper.writeValueAsString(java.util.Map.of(
                        "name", name, "url", endpointUrl, "secret", TEST_SECRET
                ))
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> post(String path, String body) {
        return postWithKey(path, null, body);
    }

    private ResponseEntity<JsonNode> postWithKey(String path, String idempotencyKey, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return restTemplate.exchange(
                url(path),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private List<ResponseEntity<JsonNode>> concurrentlySubmit(
            String firstKey,
            String firstBody,
            String secondKey,
            String secondBody
    ) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        executor = Executors.newFixedThreadPool(2);
        Future<ResponseEntity<JsonNode>> first = executor.submit(() -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return postWithKey("/api/events", firstKey, firstBody);
        });
        Future<ResponseEntity<JsonNode>> second = executor.submit(() -> {
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return postWithKey("/api/events", secondKey, secondBody);
        });
        start.countDown();
        return List.of(
                first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS)
        );
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
