package com.kemselcuk.webhook.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Moves due retry state back to delivery work and creates its outbox intent. */
@Repository
public class DeliveryRetryRequeueStore {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryRetryRequeueStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public int requeueDue(int batchSize, Instant now) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }

        List<UUID> deliveryIds = jdbcTemplate.query(
                """
                SELECT d.id
                FROM deliveries d
                JOIN webhook_endpoints endpoint
                  ON endpoint.id = d.webhook_endpoint_id
                WHERE d.status = 'RETRY_SCHEDULED'
                  AND d.next_retry_at <= ?
                  AND endpoint.enabled = TRUE
                ORDER BY d.next_retry_at, d.id
                FOR UPDATE OF d SKIP LOCKED
                LIMIT ?
                """,
                statement -> {
                    statement.setObject(1, asOffsetDateTime(now));
                    statement.setInt(2, batchSize);
                },
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class)
        );

        int requeued = 0;
        for (UUID deliveryId : deliveryIds) {
            int updated = jdbcTemplate.update(
                    """
                    UPDATE deliveries d
                    SET status = 'PENDING',
                        next_retry_at = NULL,
                        updated_at = ?
                    FROM webhook_endpoints endpoint
                    WHERE d.id = ?
                      AND d.webhook_endpoint_id = endpoint.id
                      AND d.status = 'RETRY_SCHEDULED'
                      AND d.next_retry_at <= ?
                      AND endpoint.enabled = TRUE
                    """,
                    asOffsetDateTime(now),
                    deliveryId,
                    asOffsetDateTime(now)
            );
            if (updated != 1) {
                continue;
            }

            jdbcTemplate.update(
                    """
                    INSERT INTO outbox_events (
                        id, delivery_id, event_type, payload, status,
                        available_at, claim_token, claimed_at, published_at,
                        publish_attempts, last_error, created_at, updated_at
                    ) VALUES (?, ?, 'DELIVERY_REQUESTED',
                              jsonb_build_object('version', 1, 'deliveryId', ?),
                              'PENDING', ?, NULL, NULL, NULL,
                              0, NULL, ?, ?)
                    """,
                    UUID.randomUUID(),
                    deliveryId,
                    deliveryId.toString(),
                    asOffsetDateTime(now),
                    asOffsetDateTime(now),
                    asOffsetDateTime(now)
            );
            requeued++;
        }
        return requeued;
    }

    private static OffsetDateTime asOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
