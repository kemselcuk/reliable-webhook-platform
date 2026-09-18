package com.kemselcuk.webhook.domain;

import com.kemselcuk.webhook.security.SigningSecret;
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

/** Durable, non-serializable endpoint signing material and rotation metadata. */
@Entity
@Table(name = "webhook_endpoint_secrets")
public class WebhookEndpointSecret {

    public static final int INITIAL_VERSION = 1;
    public static final String INITIAL_KEY_ID = "v1";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "webhook_endpoint_id", nullable = false)
    private WebhookEndpoint endpoint;

    @Column(name = "secret_version", nullable = false)
    private int secretVersion;

    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "secret_material", nullable = false, columnDefinition = "bytea")
    private byte[] secretMaterial;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEndpointSecret() {
        // Required by JPA.
    }

    private WebhookEndpointSecret(
            WebhookEndpoint endpoint,
            int secretVersion,
            String keyId,
            SigningSecret secret,
            boolean active
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.secretVersion = requireVersion(secretVersion);
        this.keyId = requireKeyId(keyId);
        this.secretMaterial = Objects.requireNonNull(secret, "secret").bytes();
        this.active = active;
    }

    public static WebhookEndpointSecret initial(
            WebhookEndpoint endpoint,
            SigningSecret secret
    ) {
        return new WebhookEndpointSecret(
                endpoint, INITIAL_VERSION, INITIAL_KEY_ID, secret, true
        );
    }

    public UUID getId() {
        return id;
    }

    public int getSecretVersion() {
        return secretVersion;
    }

    public String getKeyId() {
        return keyId;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "WebhookEndpointSecret{" +
                "id=" + id +
                ", endpointId=" + (endpoint == null ? null : endpoint.getId()) +
                ", secretVersion=" + secretVersion +
                ", keyId='" + keyId + '\'' +
                ", active=" + active +
                ", createdAt=" + createdAt +
                '}';
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    private static int requireVersion(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("secretVersion must be positive");
        }
        return value;
    }

    private static String requireKeyId(String value) {
        Objects.requireNonNull(value, "keyId");
        if (value.isBlank() || value.length() > 64) {
            throw new IllegalArgumentException("keyId must contain 1 to 64 characters");
        }
        return value;
    }
}
