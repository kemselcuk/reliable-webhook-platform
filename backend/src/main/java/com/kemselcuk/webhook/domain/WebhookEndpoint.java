package com.kemselcuk.webhook.domain;

import com.kemselcuk.webhook.security.SigningSecret;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "webhookEndpoint", fetch = FetchType.LAZY)
    private final List<Delivery> deliveries = new ArrayList<>();

    @OneToMany(mappedBy = "endpoint", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private final List<WebhookEndpointSecret> signingSecrets = new ArrayList<>();

    protected WebhookEndpoint() {
        // Required by JPA.
    }

    private WebhookEndpoint(String name, String url) {
        this(name, url, SigningSecret.generate());
    }

    private WebhookEndpoint(String name, String url, SigningSecret secret) {
        this.name = requireText(name, "name");
        this.url = requireText(url, "url");
        this.enabled = true;
        signingSecrets.add(WebhookEndpointSecret.initial(this, secret));
    }

    public static WebhookEndpoint create(String name, String url) {
        return new WebhookEndpoint(name, url);
    }

    public static WebhookEndpoint create(String name, String url, SigningSecret secret) {
        return new WebhookEndpoint(name, url, Objects.requireNonNull(secret, "secret"));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void disable() {
        enabled = false;
    }

    public void enable() {
        enabled = true;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
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

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
