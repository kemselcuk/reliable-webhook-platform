package com.kemselcuk.webhook.outbox;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        properties = {
                "webhook.outbox.publisher.enabled=true",
                "webhook.outbox.publisher.poll-interval=PT1H",
                "webhook.outbox.publisher.send-timeout=PT10S"
        }
)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxKafkaPublisherIT {

    private static final String TOPIC = "webhook.delivery.commands.v1";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_outbox_kafka_test")
            .withUsername("webhook_outbox_kafka_test")
            .withPassword("webhook_outbox_kafka_test");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:4.3.1")
    );

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private DeliveryRepository deliveryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void clearDatabase() {
        outboxEventRepository.deleteAllInBatch();
        deliveryRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        endpointRepository.deleteAllInBatch();
    }

    @Test
    void sendsCompactDeliveryCommandAndMarksOutboxPublished() throws Exception {
        OutboxEvent outboxEvent = seedOutbox();
        assertThat(publisher.publishSingleCycle()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, outboxEvent.getId()
        )).isEqualTo("PUBLISHED");

        Properties consumerProperties = new Properties();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID());
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProperties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProperties)) {
            consumer.subscribe(List.of(TOPIC));
            assertThat(consumer.partitionsFor(TOPIC)).hasSize(3);
            ConsumerRecord<String, String> record = awaitRecord(consumer);

            assertThat(record.topic()).isEqualTo(TOPIC);
            assertThat(record.key()).isEqualTo(outboxEvent.getDelivery().getId().toString());
            JsonNode value = objectMapper.readTree(record.value());
            assertThat(value).hasSize(2);
            assertThat(value.get("version").asInt()).isEqualTo(1);
            assertThat(value.get("deliveryId").asText())
                    .isEqualTo(outboxEvent.getDelivery().getId().toString());
        }
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("timed out waiting for a Kafka delivery command");
    }

    private OutboxEvent seedOutbox() throws Exception {
        WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                WebhookEndpoint.create("Orders", "https://orders.example.test/hooks")
        );
        Event event = eventRepository.saveAndFlush(Event.create(
                "order.created",
                objectMapper.readTree("{\"orderId\":\"order-123\"}")
        ));
        Delivery delivery = deliveryRepository.saveAndFlush(Delivery.create(event, endpoint));
        return outboxEventRepository.saveAndFlush(OutboxEvent.forDelivery(delivery));
    }
}
