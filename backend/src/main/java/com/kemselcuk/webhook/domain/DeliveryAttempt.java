package com.kemselcuk.webhook.domain;

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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts", uniqueConstraints = @UniqueConstraint(
        name = "delivery_attempts_delivery_number_unique",
        columnNames = {"delivery_id", "attempt_number"}
))
public class DeliveryAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "delivery_id", nullable = false)
    private Delivery delivery;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeliveryAttemptOutcome outcome;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_code", length = 128)
    private String errorCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    protected DeliveryAttempt() {
        // Required by JPA.
    }

    private DeliveryAttempt(
            Delivery delivery,
            int attemptNumber,
            DeliveryAttemptOutcome outcome,
            Integer httpStatus,
            String errorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        this.attemptNumber = attemptNumber;
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt must not be before startedAt");
        }
    }

    public static DeliveryAttempt create(
            Delivery delivery,
            int attemptNumber,
            DeliveryAttemptOutcome outcome,
            Integer httpStatus,
            String errorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        return new DeliveryAttempt(
                delivery, attemptNumber, outcome, httpStatus, errorCode, startedAt, completedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public DeliveryAttemptOutcome getOutcome() {
        return outcome;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
