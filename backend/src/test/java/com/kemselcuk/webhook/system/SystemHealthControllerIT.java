package com.kemselcuk.webhook.system;

import com.kemselcuk.webhook.WebhookPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = WebhookPlatformApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SystemHealthControllerIT {

    @LocalServerPort
    private int port;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    @Test
    void exposesHealthOverHttp() {
        ResponseEntity<SystemHealthResponse> response = restTemplate.getForEntity(
                "http://localhost:{port}/api/system/health",
                SystemHealthResponse.class,
                port
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(
                new SystemHealthResponse("UP", "reliable-webhook-platform")
        );
    }
}
