package com.kemselcuk.webhook.system;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record SystemSummaryResponse(
        String status,
        String service,
        Instant generatedAt,
        long endpointCount,
        long eventCount,
        long pendingOutbox,
        long retryBacklog,
        long acceptedEvents,
        long deliveryIntents,
        Map<String, Long> deliveriesByStatus
) {

    public SystemSummaryResponse {
        deliveriesByStatus = Collections.unmodifiableMap(new LinkedHashMap<>(deliveriesByStatus));
    }
}
