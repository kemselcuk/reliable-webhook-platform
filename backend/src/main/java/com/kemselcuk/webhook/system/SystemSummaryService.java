package com.kemselcuk.webhook.system;

import com.kemselcuk.webhook.domain.DeliveryStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SystemSummaryService {

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public SystemSummaryService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public SystemSummaryResponse currentSummary() {
        return new SystemSummaryResponse(
                "UP",
                "reliable-webhook-platform",
                Instant.now(clock),
                count("SELECT COUNT(*) FROM webhook_endpoints"),
                count("SELECT COUNT(*) FROM events"),
                count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'"),
                count("SELECT COUNT(*) FROM deliveries WHERE status = 'RETRY_SCHEDULED'"),
                counterValue("webhook.events.accepted"),
                counterValue("webhook.delivery.intents"),
                deliveryCounts()
        );
    }

    private Map<String, Long> deliveryCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DeliveryStatus status : DeliveryStatus.values()) {
            counts.put(status.name(), 0L);
        }
        jdbcTemplate.query(
                "SELECT status, COUNT(*) FROM deliveries GROUP BY status",
                resultSet -> {
                    String status = resultSet.getString(1);
                    if (counts.containsKey(status)) {
                        counts.put(status, resultSet.getLong(2));
                    }
                }
        );
        return counts;
    }

    private long count(String sql) {
        Number value = jdbcTemplate.queryForObject(sql, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private long counterValue(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter == null ? 0L : Math.round(counter.count());
    }
}
