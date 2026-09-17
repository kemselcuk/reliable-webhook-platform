package com.kemselcuk.webhook.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    public static final String DELIVERY_REQUESTED_EVENT_TYPE = "DELIVERY_REQUESTED";
    private static final int PAYLOAD_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "event_type", nullable = false, length = 128)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "publish_attempts", nullable = false)
    private int publishAttempts;

    @Column(name = "last_error", length = 2048)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEvent() {
        // Required by JPA.
    }

    private OutboxEvent(Delivery delivery) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        UUID deliveryId = Objects.requireNonNull(
                delivery.getId(), "delivery must be persisted before creating an outbox event"
        );
        this.eventType = DELIVERY_REQUESTED_EVENT_TYPE;
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("version", PAYLOAD_VERSION);
        payload.put("deliveryId", deliveryId.toString());
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.publishAttempts = 0;
    }

    public static OutboxEvent forDelivery(Delivery delivery) {
        return new OutboxEvent(delivery);
    }

    public UUID getId() {
        return id;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public String getEventType() {
        return eventType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public UUID getClaimToken() {
        return claimToken;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getPublishAttempts() {
        return publishAttempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (availableAt == null) {
            availableAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
