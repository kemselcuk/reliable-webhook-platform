package com.kemselcuk.webhook.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemHealthServiceTest {

    private final SystemHealthService service = new SystemHealthService();

    @Test
    void reportsTheFoundationServiceAsUp() {
        SystemHealthResponse response = service.currentHealth();

        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.service()).isEqualTo("reliable-webhook-platform");
    }
}
