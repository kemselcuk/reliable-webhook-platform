package com.kemselcuk.webhook.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class OutboxClaimStore implements OutboxClaimOperations {

    private static final Set<String> FAILURE_CATEGORIES = Set.of(
            "INTERRUPTED",
            "TIMEOUT",
            "KAFKA",
            "SERIALIZATION",
            "PUBLISH_FAILURE"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxClaimStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<OutboxClaim> claimBatch(int batchSize, Instant now, Duration claimTimeout) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (claimTimeout.isNegative() || claimTimeout.isZero()) {
            throw new IllegalArgumentException("claimTimeout must be positive");
        }

        UUID claimToken = UUID.randomUUID();
        Instant staleBefore = now.minus(claimTimeout);
        return List.copyOf(jdbcTemplate.query("""
                WITH candidates AS (
                    SELECT id
                    FROM outbox_events
                    WHERE (status = 'PENDING' AND available_at <= ?)
                       OR (status = 'CLAIMED' AND claimed_at <= ?)
                    ORDER BY available_at, created_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE outbox_events AS outbox
                SET status = 'CLAIMED',
                    claim_token = ?,
                    claimed_at = ?,
                    publish_attempts = outbox.publish_attempts + 1,
                    updated_at = ?
                FROM candidates
                WHERE outbox.id = candidates.id
                RETURNING outbox.id, outbox.delivery_id, outbox.payload, outbox.claim_token
                """,
                preparedStatement -> {
                    preparedStatement.setObject(1, asOffsetDateTime(now));
                    preparedStatement.setObject(2, asOffsetDateTime(staleBefore));
                    preparedStatement.setInt(3, batchSize);
                    preparedStatement.setObject(4, claimToken);
                    preparedStatement.setObject(5, asOffsetDateTime(now));
                    preparedStatement.setObject(6, asOffsetDateTime(now));
                },
                this::toClaim
        ));
    }

    @Override
    @Transactional
    public boolean markPublished(OutboxClaim claim, Instant publishedAt) {
        return jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PUBLISHED',
                    claim_token = NULL,
                    claimed_at = NULL,
                    published_at = ?,
                    last_error = NULL,
                    updated_at = ?
                WHERE id = ?
                  AND claim_token = ?
                  AND status = 'CLAIMED'
                """, asOffsetDateTime(publishedAt), asOffsetDateTime(publishedAt),
                claim.id(), claim.claimToken()) == 1;
    }

    @Override
    @Transactional
    public boolean releaseForRetry(
            OutboxClaim claim,
            Instant now,
            Instant availableAt,
            String failureCategory
    ) {
        String safeCategory = sanitizeFailureCategory(failureCategory);
        return jdbcTemplate.update("""
                UPDATE outbox_events
                SET status = 'PENDING',
                    claim_token = NULL,
                    claimed_at = NULL,
                    available_at = ?,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                  AND claim_token = ?
                  AND status = 'CLAIMED'
                """, asOffsetDateTime(availableAt), safeCategory, asOffsetDateTime(now),
                claim.id(), claim.claimToken()) == 1;
    }

    private OutboxClaim toClaim(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            JsonNode payload = objectMapper.readTree(resultSet.getString("payload"));
            return new OutboxClaim(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("delivery_id", UUID.class),
                    payload,
                    resultSet.getObject("claim_token", UUID.class)
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("outbox payload is not valid JSON", exception);
        }
    }

    private static String sanitizeFailureCategory(String failureCategory) {
        return FAILURE_CATEGORIES.contains(failureCategory) ? failureCategory : "PUBLISH_FAILURE";
    }

    private static OffsetDateTime asOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
