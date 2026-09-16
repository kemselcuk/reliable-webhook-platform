package com.kemselcuk.webhook.domain.repository;

import com.kemselcuk.webhook.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
}
