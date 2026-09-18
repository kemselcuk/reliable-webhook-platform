package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.Options;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import com.kemselcuk.webhook.domain.repository.DeliveryAttemptRepository;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webhook.outbox.publisher.enabled=true",
                "webhook.outbox.publisher.poll-interval=PT0.1S",
                "webhook.outbox.publisher.retry-delay=PT0.1S",
                "webhook.outbox.publisher.send-timeout=PT5S",
                "webhook.delivery.worker.enabled=true",
                "webhook.delivery.worker.claim-timeout=PT5M",
                "webhook.delivery.worker.connect-timeout=PT2S",
                "webhook.delivery.worker.response-timeout=PT2S",
                "webhook.delivery.worker.concurrency=3",
                "webhook.delivery.worker.busy-redelivery-delay=PT0.2S",
                "spring.kafka.consumer.properties.max.poll.interval.ms=30000"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeliveryPipelineIT {

    private static final String TOPIC = "webhook.delivery.commands.v1";
    private static final String GROUP_ID = "delivery-pipeline-it-" + UUID.randomUUID();
    private static final String PATH = "/webhooks/orders";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_delivery_pipeline_test")
            .withUsername("webhook_delivery_pipeline_test")
            .withPassword("webhook_delivery_pipeline_test");

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
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository deliveryAttemptRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void deliversApiEventAsynchronouslyAndIgnoresDuplicateCommand() throws Exception {
        String payload = "{\"orderId\":\"order-123\",\"items\":[{\"sku\":\"sku-1\",\"quantity\":2}]}";
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accepted\":true}")));

        ResponseEntity<JsonNode> endpointResponse = post(
                "/api/webhook-endpoints",
                objectMapper.createObjectNode()
                        .put("name", "Orders")
                        .put("url", wireMock.baseUrl() + PATH)
                        .put("secret", "ssssssssssssssssssssssssssssssss")
        );
        assertThat(endpointResponse.getStatusCode().value()).isEqualTo(201);
        JsonNode endpoint = endpointResponse.getBody();
        assertThat(endpoint).isNotNull();
        UUID endpointId = UUID.fromString(endpoint.get("id").asText());

        ObjectNode eventRequest = objectMapper.createObjectNode();
        eventRequest.put("type", "order.created");
        eventRequest.set("payload", objectMapper.readTree(payload));
        eventRequest.set("endpointIds", objectMapper.createArrayNode().add(endpointId.toString()));
        ResponseEntity<JsonNode> eventResponse = post("/api/events", eventRequest);
        assertThat(eventResponse.getStatusCode().value()).isEqualTo(201);
        JsonNode event = eventResponse.getBody();
        assertThat(event).isNotNull();
        UUID eventId = UUID.fromString(event.get("id").asText());
        UUID deliveryId = UUID.fromString(event.get("deliveryIds").get(0).asText());

        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                    .withRequestBody(equalToJson(payload, false, false))
                    .withHeader("Content-Type", equalTo("application/json"))
                    .withHeader("Accept", equalTo("application/json"))
                    .withHeader("X-Webhook-Id", equalTo(eventId.toString()))
                    .withHeader("X-Delivery-Id", equalTo(deliveryId.toString()))
                    .withHeader("X-Webhook-Event", equalTo("order.created")));

            assertThat(deliveryRepository.findById(deliveryId).orElseThrow().getStatus())
                    .isEqualTo(DeliveryStatus.SUCCESS);
            assertThat(deliveryRepository.findById(deliveryId).orElseThrow().getAttemptCount())
                    .isEqualTo(1);
            assertThat(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId))
                    .singleElement()
                    .satisfies(attempt -> {
                        assertThat(attempt.getOutcome()).isEqualTo(DeliveryAttemptOutcome.SUCCESS);
                        assertThat(attempt.getHttpStatus()).isEqualTo(202);
                    });
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM outbox_events WHERE delivery_id = ?", String.class, deliveryId
            )).isEqualTo("PUBLISHED");
        });

        String duplicateValue = "{\"version\":1,\"deliveryId\":\"%s\"}".formatted(deliveryId);
        RecordMetadata duplicateMetadata = kafkaTemplate.send(TOPIC, deliveryId.toString(), duplicateValue)
                .get(10, TimeUnit.SECONDS)
                .getRecordMetadata();
        awaitForDuplicateCommit(duplicateMetadata);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
            assertThat(deliveryRepository.findById(deliveryId).orElseThrow().getStatus())
                    .isEqualTo(DeliveryStatus.SUCCESS);
            assertThat(deliveryRepository.findById(deliveryId).orElseThrow().getAttemptCount())
                    .isEqualTo(1);
            assertThat(deliveryAttemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId))
                    .hasSize(1);
        });
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
    }

    private void awaitForDuplicateCommit(RecordMetadata metadata) {
        Map<String, Object> adminProperties = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()
        );
        try (AdminClient adminClient = AdminClient.create(adminProperties)) {
            TopicPartition partition = new TopicPartition(TOPIC, metadata.partition());
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                try {
                    var offsets = adminClient.listConsumerGroupOffsets(GROUP_ID)
                            .partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS);
                    assertThat(offsets.get(partition)).isNotNull();
                    assertThat(offsets.get(partition).offset()).isGreaterThan(metadata.offset());
                } catch (Exception exception) {
                    throw new AssertionError("timed out waiting for duplicate command commit", exception);
                }
            });
        }
    }

    private ResponseEntity<JsonNode> post(String path, JsonNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.POST,
                new HttpEntity<>(body.toString(), headers),
                JsonNode.class
        );
    }
}
