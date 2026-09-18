package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import com.kemselcuk.webhook.security.SigningSecret;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Short PostgreSQL transactions around a delivery worker's external work.
 *
 * <p>Claiming loads all worker inputs before the transaction commits. The
 * completion methods only accept the current lease token, so a worker that
 * outlives its lease cannot modify a replacement worker's state.</p>
 */
@Repository
public class DeliveryClaimStore {

    private static final int MAX_ERROR_CODE_LENGTH = 128;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DeliveryClaimStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Atomically acquire a fresh token for one eligible delivery and load its
     * current event and endpoint state in the same short transaction.
     */
    @Transactional
    public DeliveryClaimResult claim(UUID deliveryId, Instant now, Duration claimTimeout) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(now, "now");
        validatePositive(claimTimeout, "claimTimeout");

        Instant staleBefore = now.minus(claimTimeout);
        UUID claimToken = UUID.randomUUID();
        List<DeliveryWorkSnapshot> claimed = jdbcTemplate.query(
                """
                WITH claimed AS (
                    UPDATE deliveries d
                    SET status = 'PROCESSING',
                        claim_token = ?,
                        claimed_at = ?,
                        updated_at = ?
                    FROM webhook_endpoints endpoint
                    WHERE d.id = ?
                      AND endpoint.id = d.webhook_endpoint_id
                      AND endpoint.enabled = TRUE
                      AND EXISTS (
                          SELECT 1
                          FROM webhook_endpoint_secrets active_secret
                          WHERE active_secret.webhook_endpoint_id = endpoint.id
                            AND active_secret.active = TRUE
                      )
                      AND (
                          d.status = 'PENDING'
                          OR (d.status = 'PROCESSING' AND d.claimed_at <= ?)
                      )
                    RETURNING d.id, d.event_id, d.webhook_endpoint_id,
                              d.attempt_count, d.run_attempt_count, d.claim_token
                )
                SELECT claimed.id AS delivery_id,
                       claimed.event_id,
                       claimed.webhook_endpoint_id AS endpoint_id,
                       event.event_type,
                       event.payload::text AS payload,
                       endpoint.url AS endpoint_url,
                       endpoint.enabled AS endpoint_enabled,
                       active_secret.key_id AS signing_key_id,
                       active_secret.secret_material,
                       claimed.claim_token,
                       claimed.attempt_count + 1 AS next_attempt_number,
                       claimed.run_attempt_count + 1 AS current_run_attempt_number
                FROM claimed
                JOIN events event ON event.id = claimed.event_id
                JOIN webhook_endpoints endpoint
                  ON endpoint.id = claimed.webhook_endpoint_id
                JOIN webhook_endpoint_secrets active_secret
                  ON active_secret.webhook_endpoint_id = claimed.webhook_endpoint_id
                 AND active_secret.active = TRUE
                """,
                statement -> {
                    statement.setObject(1, claimToken);
                    statement.setObject(2, asOffsetDateTime(now));
                    statement.setObject(3, asOffsetDateTime(now));
                    statement.setObject(4, deliveryId);
                    statement.setObject(5, asOffsetDateTime(staleBefore));
                },
                this::toWorkSnapshot
        );
        if (!claimed.isEmpty()) {
            return DeliveryClaimResult.claimed(claimed.getFirst());
        }
        return readUnclaimable(deliveryId, staleBefore);
    }

    /**
     * Complete a successful HTTP exchange if this worker still owns the
     * lease. The state transition and attempt insert commit together.
     */
    @Transactional
    public boolean completeSuccess(
            DeliveryWorkSnapshot work,
            Integer httpStatus,
            Instant startedAt,
            Instant completedAt
    ) {
        Objects.requireNonNull(work, "work");
        return completeSuccess(
                work.deliveryId(), work.claimToken(), httpStatus, startedAt, completedAt
        );
    }

    /**
     * Token-guarded successful completion by delivery identity.
     */
    @Transactional
    public boolean completeSuccess(
            UUID deliveryId,
            UUID claimToken,
            Integer httpStatus,
            Instant startedAt,
            Instant completedAt
    ) {
        validateCompletionIdentity(deliveryId, claimToken);
        validateHttpStatus(httpStatus, true);
        validateTimestamps(startedAt, completedAt);
        return complete(
                deliveryId,
                claimToken,
                DeliveryStatus.SUCCESS,
                DeliveryAttemptOutcome.SUCCESS,
                httpStatus,
                null,
                null,
                startedAt,
                completedAt
        );
    }

    /**
     * Complete a classified failure and persist its durable retry decision if
     * this worker still owns the lease.
     */
    @Transactional
    public boolean completeFailure(
            DeliveryWorkSnapshot work,
            DeliveryRetryDecision decision,
            Integer httpStatus,
            Instant startedAt,
            Instant completedAt
    ) {
        Objects.requireNonNull(work, "work");
        validateCompletionIdentity(work.deliveryId(), work.claimToken());
        Objects.requireNonNull(decision, "decision");
        validateHttpStatus(httpStatus, false);
        validateTimestamps(startedAt, completedAt);
        return complete(
                work.deliveryId(),
                work.claimToken(),
                decision.targetStatus(),
                decision.outcome(),
                httpStatus,
                validateErrorCode(decision.errorCode()),
                decision.nextRetryAt(),
                startedAt,
                completedAt
        );
    }

    private boolean complete(
            UUID deliveryId,
            UUID claimToken,
            DeliveryStatus status,
            DeliveryAttemptOutcome outcome,
            Integer httpStatus,
            String errorCode,
            Instant nextRetryAt,
            Instant startedAt,
            Instant completedAt
    ) {
        List<Integer> attemptCounts = jdbcTemplate.query(
                """
                UPDATE deliveries
                SET status = ?,
                    next_retry_at = ?,
                    claim_token = NULL,
                    claimed_at = NULL,
                    attempt_count = attempt_count + 1,
                    run_attempt_count = run_attempt_count + 1,
                    updated_at = ?
                WHERE id = ?
                  AND claim_token = ?
                  AND status = 'PROCESSING'
                RETURNING attempt_count
                """,
                statement -> {
                    statement.setString(1, status.name());
                    statement.setObject(2, nextRetryAt == null ? null : asOffsetDateTime(nextRetryAt));
                    statement.setObject(3, asOffsetDateTime(completedAt));
                    statement.setObject(4, deliveryId);
                    statement.setObject(5, claimToken);
                },
                (resultSet, rowNumber) -> resultSet.getInt("attempt_count")
        );
        if (attemptCounts.isEmpty()) {
            return false;
        }

        jdbcTemplate.update(
                """
                INSERT INTO delivery_attempts (
                    id, delivery_id, attempt_number, outcome, http_status,
                    error_code, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                deliveryId,
                attemptCounts.getFirst(),
                outcome.name(),
                httpStatus,
                errorCode,
                asOffsetDateTime(startedAt),
                asOffsetDateTime(completedAt)
        );
        return true;
    }

    private DeliveryClaimResult readUnclaimable(
            UUID deliveryId,
            Instant staleBefore
    ) {
        List<ClaimState> states = jdbcTemplate.query(
                """
                SELECT d.status, d.claimed_at, endpoint.enabled
                FROM deliveries d
                JOIN webhook_endpoints endpoint
                  ON endpoint.id = d.webhook_endpoint_id
                WHERE d.id = ?
                """,
                ps -> ps.setObject(1, deliveryId),
                (resultSet, rowNumber) -> new ClaimState(
                        DeliveryStatus.valueOf(resultSet.getString("status")),
                        asInstant(resultSet.getTimestamp("claimed_at")),
                        resultSet.getBoolean("enabled")
                )
        );
        if (states.isEmpty()) {
            return DeliveryClaimResult.unclaimable(
                    DeliveryClaimDisposition.NOT_FOUND, null
            );
        }

        ClaimState state = states.getFirst();
        if (!state.endpointEnabled()) {
            return DeliveryClaimResult.unclaimable(
                    DeliveryClaimDisposition.DISABLED, state.status()
            );
        }
        if (state.status() == DeliveryStatus.SUCCESS || state.status() == DeliveryStatus.DEAD) {
            return DeliveryClaimResult.unclaimable(
                    DeliveryClaimDisposition.TERMINAL, state.status()
            );
        }
        if (state.status() == DeliveryStatus.PROCESSING
                && state.claimedAt() != null
                && state.claimedAt().isAfter(staleBefore)) {
            return DeliveryClaimResult.unclaimable(
                    DeliveryClaimDisposition.BUSY, state.status()
            );
        }
        return DeliveryClaimResult.unclaimable(
                DeliveryClaimDisposition.INELIGIBLE, state.status()
        );
    }

    private DeliveryWorkSnapshot toWorkSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            JsonNode payload = objectMapper.readTree(resultSet.getString("payload"));
            return new DeliveryWorkSnapshot(
                    resultSet.getObject("delivery_id", UUID.class),
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getObject("endpoint_id", UUID.class),
                    resultSet.getString("event_type"),
                    payload,
                    resultSet.getString("endpoint_url"),
                    resultSet.getBoolean("endpoint_enabled"),
                    resultSet.getObject("claim_token", UUID.class),
                    resultSet.getInt("next_attempt_number"),
                    resultSet.getInt("current_run_attempt_number"),
                    resultSet.getString("signing_key_id"),
                    SigningSecret.fromBytes(resultSet.getBytes("secret_material"))
            );
        } catch (JsonProcessingException exception) {
            throw new SQLException("delivery payload is not valid JSON", exception);
        }
    }

    private static Instant asInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static OffsetDateTime asOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static void validateCompletionIdentity(UUID deliveryId, UUID claimToken) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(claimToken, "claimToken");
    }

    private static void validatePositive(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }

    private static void validateHttpStatus(Integer httpStatus, boolean success) {
        if (httpStatus == null) {
            if (success) {
                throw new IllegalArgumentException("successful completion requires httpStatus");
            }
            return;
        }
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        if (success && (httpStatus < 200 || httpStatus > 299)) {
            throw new IllegalArgumentException("successful httpStatus must be between 200 and 299");
        }
    }

    private static String validateErrorCode(String errorCode) {
        if (errorCode == null) {
            return null;
        }
        String bounded = errorCode.trim();
        if (bounded.isEmpty()) {
            throw new IllegalArgumentException("errorCode must not be blank");
        }
        if (bounded.length() > MAX_ERROR_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "errorCode must not exceed " + MAX_ERROR_CODE_LENGTH + " characters"
            );
        }
        for (int index = 0; index < bounded.length(); index++) {
            char character = bounded.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '_' || character == '-' || character == '.'
                    || character == ':' || character == '/')) {
                throw new IllegalArgumentException("errorCode contains unsupported characters");
            }
        }
        return bounded;
    }

    private static void validateTimestamps(Instant startedAt, Instant completedAt) {
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    private record ClaimState(DeliveryStatus status, Instant claimedAt, boolean endpointEnabled) {
    }
}
