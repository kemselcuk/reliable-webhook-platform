package com.kemselcuk.webhook.observability;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * Scoped, correlation-only MDC values for structured logs.
 *
 * <p>Only stable identifiers are placed in the logging context. Payloads,
 * endpoint URLs, secrets, and exception messages are intentionally excluded.</p>
 */
public final class LogContext implements AutoCloseable {

    private final Map<String, String> previous;

    private LogContext(Map<String, String> previous) {
        this.previous = previous;
    }

    public static LogContext event(UUID eventId) {
        return with(Map.of("event_id", eventId.toString()));
    }

    public static LogContext delivery(UUID deliveryId, UUID eventId) {
        return with(Map.of(
                "delivery_id", deliveryId.toString(),
                "event_id", eventId.toString()
        ));
    }

    public static LogContext delivery(UUID deliveryId) {
        return with(Map.of("delivery_id", deliveryId.toString()));
    }

    private static LogContext with(Map<String, String> values) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        values.forEach(MDC::put);
        return new LogContext(previous);
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(previous);
        }
    }
}
