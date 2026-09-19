package com.kemselcuk.webhook.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LogContextTest {

    @Test
    void scopesOnlyCorrelationIdentifiersAndRestoresPreviousContext() {
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        MDC.put("request_id", "request-1");

        try (LogContext ignored = LogContext.delivery(deliveryId, eventId)) {
            assertThat(MDC.get("request_id")).isEqualTo("request-1");
            assertThat(MDC.get("event_id")).isEqualTo(eventId.toString());
            assertThat(MDC.get("delivery_id")).isEqualTo(deliveryId.toString());
            assertThat(MDC.getCopyOfContextMap()).doesNotContainKeys(
                    "payload", "secret", "url", "endpoint_url"
            );
        }

        assertThat(MDC.get("request_id")).isEqualTo("request-1");
        assertThat(MDC.get("event_id")).isNull();
        assertThat(MDC.get("delivery_id")).isNull();
        MDC.clear();
    }
}
