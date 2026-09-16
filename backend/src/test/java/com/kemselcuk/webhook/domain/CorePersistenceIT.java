package com.kemselcuk.webhook.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.WebhookPlatformApplication;
import com.kemselcuk.webhook.domain.repository.DeliveryAttemptRepository;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import org.flywaydb.core.Flyway;
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

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(classes = WebhookPlatformApplication.class)
class CorePersistenceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_test")
            .withUsername("webhook_test")
            .withPassword("webhook_test");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private DeliveryAttemptRepository attemptRepository;

    @Test
    void flywayCreatesValidatedSchemaAndPersistsCoreGraph() throws Exception {
        flyway.validate();

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN ('webhook_endpoints', 'events', 'deliveries', 'delivery_attempts')
                """, Integer.class)).isEqualTo(4);

        JsonNode payload = objectMapper.readTree("""
                {"orderId":"order-123","total":42.50,"items":[{"sku":"sku-1","quantity":2}]}
                """);
        Instant startedAt = Instant.parse("2026-01-01T10:00:00Z");
        Instant completedAt = Instant.parse("2026-01-01T10:00:01Z");

        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create("Orders", "https://example.test/webhooks")
        );
        Event event = eventRepository.saveAndFlush(Event.create("order.created", payload));
        Delivery delivery = deliveryRepository.saveAndFlush(Delivery.create(event, endpoint));
        DeliveryAttempt attempt = attemptRepository.saveAndFlush(DeliveryAttempt.create(
                delivery,
                1,
                DeliveryAttemptOutcome.SUCCESS,
                200,
                null,
                startedAt,
                completedAt
        ));

        Event loadedEvent = eventRepository.findById(event.getId()).orElseThrow();
        Delivery loadedDelivery = deliveryRepository.findById(delivery.getId()).orElseThrow();

        assertThat(loadedEvent.getPayload()).isEqualTo(payload);
        assertThat(loadedDelivery.getEvent().getId()).isEqualTo(event.getId());
        assertThat(loadedDelivery.getWebhookEndpoint().getId()).isEqualTo(endpoint.getId());
        assertThat(attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(delivery.getId()))
                .extracting(DeliveryAttempt::getAttemptNumber)
                .containsExactly(1);
        assertThat(attempt.getCompletedAt()).isEqualTo(completedAt);

        assertThatThrownBy(() -> attemptRepository.saveAndFlush(DeliveryAttempt.create(
                delivery,
                1,
                DeliveryAttemptOutcome.PERMANENT_FAILURE,
                400,
                "invalid_request",
                startedAt,
                completedAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }
}
