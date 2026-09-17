package com.kemselcuk.webhook.domain.repository;

import com.kemselcuk.webhook.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
}
