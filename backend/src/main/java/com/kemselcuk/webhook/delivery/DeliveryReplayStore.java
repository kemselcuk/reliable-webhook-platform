package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Performs the locked, atomic state transition used by manual replay. */
@Repository
public class DeliveryReplayStore {

    private final JdbcTemplate jdbcTemplate;

    public DeliveryReplayStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Requeues one terminal delivery and creates its durable outbox command in
     * the same transaction. The delivery and endpoint rows remain locked until
     * both writes commit, so concurrent replay requests cannot duplicate work.
     */
    @Transactional
    public DeliveryReplayResult replay(UUID deliveryId, Instant now) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(now, "now");

        List<ReplayState> states = jdbcTemplate.query(
                """
                SELECT d.status, d.attempt_count, d.run_attempt_count, endpoint.enabled
                FROM deliveries d
                JOIN webhook_endpoints endpoint
                  ON endpoint.id = d.webhook_endpoint_id
                WHERE d.id = ?
                FOR UPDATE OF d, endpoint
                """,
                statement -> statement.setObject(1, deliveryId),
                (resultSet, rowNumber) -> new ReplayState(
                        DeliveryStatus.valueOf(resultSet.getString("status")),
                        resultSet.getInt("attempt_count"),
                        resultSet.getInt("run_attempt_count"),
                        resultSet.getBoolean("enabled")
                )
        );
        if (states.isEmpty()) {
            return DeliveryReplayResult.notFound();
        }

        ReplayState state = states.getFirst();
        if (!state.endpointEnabled()) {
            return DeliveryReplayResult.disabled();
        }
        if (state.status() != DeliveryStatus.FAILED && state.status() != DeliveryStatus.DEAD) {
            return DeliveryReplayResult.ineligible();
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE deliveries
                SET status = 'PENDING',
                    next_retry_at = NULL,
                    run_attempt_count = 0,
                    claim_token = NULL,
                    claimed_at = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND status IN ('FAILED', 'DEAD')
                """,
                asOffsetDateTime(now),
                deliveryId
        );
        if (updated != 1) {
            // This is defensive: the row lock above makes the transition
            // deterministic, but the guard protects the invariant if the SQL
            // is ever changed or called against an unexpected schema.
            return DeliveryReplayResult.ineligible();
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

        return DeliveryReplayResult.replayed(deliveryId, state.attemptCount());
    }

    private static OffsetDateTime asOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record ReplayState(
            DeliveryStatus status,
            int attemptCount,
            int runAttemptCount,
            boolean endpointEnabled
    ) {
    }
}
