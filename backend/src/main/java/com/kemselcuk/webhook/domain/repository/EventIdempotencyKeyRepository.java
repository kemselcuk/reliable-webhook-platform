package com.kemselcuk.webhook.domain.repository;

import com.kemselcuk.webhook.domain.EventIdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EventIdempotencyKeyRepository extends JpaRepository<EventIdempotencyKey, UUID> {

    Optional<EventIdempotencyKey> findByIdempotencyKey(String idempotencyKey);
}
