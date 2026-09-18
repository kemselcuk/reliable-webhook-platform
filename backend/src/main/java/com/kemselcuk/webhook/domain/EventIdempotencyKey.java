package com.kemselcuk.webhook.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "event_idempotency_keys")
public class EventIdempotencyKey {

    public static final int MAX_KEY_LENGTH = 255;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = MAX_KEY_LENGTH, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode response;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EventIdempotencyKey() {
        // Required by JPA.
    }

    private EventIdempotencyKey(
            String idempotencyKey,
            String requestHash,
            Event event,
            JsonNode response
    ) {
        this.idempotencyKey = requireKey(idempotencyKey);
        this.requestHash = requireHash(requestHash);
        this.event = Objects.requireNonNull(event, "event");
        this.response = Objects.requireNonNull(response, "response");
    }

    public static EventIdempotencyKey create(
            String idempotencyKey,
            String requestHash,
            Event event,
            JsonNode response
    ) {
        return new EventIdempotencyKey(idempotencyKey, requestHash, event, response);
    }

    public UUID getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Event getEvent() {
        return event;
    }

    public JsonNode getResponse() {
        return response;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static String requireKey(String value) {
        Objects.requireNonNull(value, "idempotencyKey");
        if (value.isBlank() || value.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        return value;
    }

    private static String requireHash(String value) {
        Objects.requireNonNull(value, "requestHash");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("requestHash must be a lowercase SHA-256 hash");
        }
        return value;
    }
}
