package com.kemselcuk.webhook.domain.repository;

import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {

    @EntityGraph(attributePaths = {"event", "webhookEndpoint"})
    Page<Delivery> findAll(Pageable pageable);

    @EntityGraph(attributePaths = {"event", "webhookEndpoint"})
    Page<Delivery> findByStatus(DeliveryStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"event", "webhookEndpoint"})
    @Override
    Optional<Delivery> findById(UUID id);
}
