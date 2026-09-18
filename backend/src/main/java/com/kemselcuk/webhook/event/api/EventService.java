package com.kemselcuk.webhook.event.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.Event;
import com.kemselcuk.webhook.domain.EventIdempotencyKey;
import com.kemselcuk.webhook.domain.OutboxEvent;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.EventIdempotencyKeyRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import com.kemselcuk.webhook.web.ApiRequestValidationException;
import com.kemselcuk.webhook.web.DisabledEndpointException;
import com.kemselcuk.webhook.web.EndpointNotFoundException;
import com.kemselcuk.webhook.web.IdempotencyKeyConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final DeliveryRepository deliveryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final EventIdempotencyKeyRepository idempotencyKeyRepository;
    private final CanonicalRequestHasher requestHasher;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    public EventService(
            EventRepository eventRepository,
            DeliveryRepository deliveryRepository,
            OutboxEventRepository outboxEventRepository,
            WebhookEndpointRepository endpointRepository,
            EventIdempotencyKeyRepository idempotencyKeyRepository,
            CanonicalRequestHasher requestHasher,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate
    ) {
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.endpointRepository = endpointRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.requestHasher = requestHasher;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
        return create(request, null);
    }

    @Transactional
    public EventResponse create(CreateEventRequest request, String requestedIdempotencyKey) {
        String idempotencyKey = normalizeIdempotencyKey(requestedIdempotencyKey);
        String requestHash = idempotencyKey == null ? null : requestHasher.hash(request);
        if (idempotencyKey != null) {
            // A unique constraint protects the table, but a read-then-insert
            // race would otherwise make one identical request fail with a
            // constraint violation. Serialize only transactions using this
            // logical key; the lock is released automatically on commit or
            // rollback and does not hold a row lock during unrelated work.
            jdbcTemplate.execute(
                    "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                    (PreparedStatementCallback<Void>) statement -> {
                        statement.setString(1, idempotencyKey);
                        statement.execute();
                        return null;
                    }
            );
            EventIdempotencyKey existing = idempotencyKeyRepository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElse(null);
            if (existing != null) {
                if (!existing.getRequestHash().equals(requestHash)) {
                    throw new IdempotencyKeyConflictException();
                }
                return storedResponse(existing);
            }
        }

        List<UUID> endpointIds = request.endpointIds();
        if (new HashSet<>(endpointIds).size() != endpointIds.size()) {
            throw new ApiRequestValidationException(
                    "endpointIds", "endpointIds must contain unique values"
            );
        }

        Map<UUID, WebhookEndpoint> endpointsById = new HashMap<>();
        endpointRepository.findAllById(endpointIds)
                .forEach(endpoint -> endpointsById.put(endpoint.getId(), endpoint));
        if (endpointsById.size() != endpointIds.size()) {
            throw new EndpointNotFoundException();
        }
        if (endpointsById.values().stream().anyMatch(endpoint -> !endpoint.isEnabled())) {
            throw new DisabledEndpointException();
        }

        Event event = eventRepository.saveAndFlush(
                Event.create(request.type().trim(), request.payload().deepCopy())
        );
        List<Delivery> deliveries = endpointIds.stream()
                .map(endpointId -> Delivery.create(event, endpointsById.get(endpointId)))
                .map(deliveryRepository::save)
                .toList();
        deliveryRepository.flush();
        deliveries.stream()
                .map(OutboxEvent::forDelivery)
                .map(outboxEventRepository::save)
                .toList();
        outboxEventRepository.flush();

        EventResponse response = new EventResponse(
                event.getId(),
                event.getEventType(),
                event.getPayload(),
                deliveries.stream().map(Delivery::getId).toList(),
                event.getCreatedAt()
        );
        if (idempotencyKey != null) {
            idempotencyKeyRepository.saveAndFlush(EventIdempotencyKey.create(
                    idempotencyKey,
                    requestHash,
                    event,
                    objectMapper.valueToTree(response)
            ));
        }
        return response;
    }

    private EventResponse storedResponse(EventIdempotencyKey idempotencyKey) {
        try {
            return objectMapper.treeToValue(idempotencyKey.getResponse(), EventResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored idempotency response cannot be deserialized", exception
            );
        }
    }

    private static String normalizeIdempotencyKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > EventIdempotencyKey.MAX_KEY_LENGTH) {
            throw new ApiRequestValidationException(
                    "Idempotency-Key",
                    "Idempotency-Key must be 1 to " + EventIdempotencyKey.MAX_KEY_LENGTH + " characters"
            );
        }
        return normalized;
    }
}
