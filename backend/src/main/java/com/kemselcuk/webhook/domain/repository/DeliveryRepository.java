package com.kemselcuk.webhook.domain.repository;

import com.kemselcuk.webhook.domain.Delivery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeliveryRepository extends JpaRepository<Delivery, UUID> {
}
