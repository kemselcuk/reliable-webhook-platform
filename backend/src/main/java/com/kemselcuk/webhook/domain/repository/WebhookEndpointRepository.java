package com.kemselcuk.webhook.domain.repository;

import com.kemselcuk.webhook.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, UUID> {
}
