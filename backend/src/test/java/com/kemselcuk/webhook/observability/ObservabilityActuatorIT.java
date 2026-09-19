package com.kemselcuk.webhook.observability;

import com.kemselcuk.webhook.WebhookPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "webhook.outbox.publisher.enabled=false",
                "webhook.delivery.worker.enabled=false",
                "webhook.delivery.retry.scheduler.enabled=false"
        }
)
class ObservabilityActuatorIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("webhook_observability_test")
            .withUsername("webhook_observability_test")
            .withPassword("webhook_observability_test");

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

    @Test
    void exposesSafeHealthAndPrometheusEndpoints() {
        ResponseEntity<String> health = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/health/readiness", String.class
        );
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(health.getBody()).doesNotContain("components", "details", "password", "secret");

        ResponseEntity<String> prometheus = restTemplate.getForEntity(
                "http://localhost:" + port + "/actuator/prometheus", String.class
        );
        assertThat(prometheus.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(prometheus.getBody())
                .contains("webhook_events_accepted_total")
                .doesNotContain("event_id", "delivery_id", "secret_material", "endpoint_url");
    }
}
