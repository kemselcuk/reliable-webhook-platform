package com.kemselcuk.webhook.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class WebhookApiIT {

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
    private DeliveryRepository deliveryRepository;

    @BeforeEach
    void clearDatabase() {
        deliveryRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    @Test
    void createsAndListsEndpointsWithNormalizedUrlAndLocation() throws Exception {
        ResponseEntity<JsonNode> createResponse = post(
                "/api/webhook-endpoints",
                """
                {"name":"Orders","url":" https://example.test/a/../webhooks "}
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
                {"name":"Payments","url":"http://payments.test/hooks"}
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
                {"name":"Orders","url":"https://orders.test/hooks"}
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
                {"name":"Orders","url":"https://orders.test/hooks"}
                """);

        ResponseEntity<JsonNode> response = post(
                "/api/webhook-endpoints",
                """
                {"name":"Orders","url":"https://another.test/hooks"}
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
                {"name":"Orders","url":"ftp://example.test/hooks#fragment"}
                """
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
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
                objectMapper.writeValueAsString(java.util.Map.of("name", name, "url", endpointUrl))
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                url(path),
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class
        );
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
