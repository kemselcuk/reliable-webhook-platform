package com.kemselcuk.webhook.event.api;

import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.Event;
import com.kemselcuk.webhook.domain.OutboxEvent;
import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.domain.repository.EventRepository;
import com.kemselcuk.webhook.domain.repository.OutboxEventRepository;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import com.kemselcuk.webhook.web.ApiRequestValidationException;
import com.kemselcuk.webhook.web.DisabledEndpointException;
import com.kemselcuk.webhook.web.EndpointNotFoundException;
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

    public EventService(
            EventRepository eventRepository,
            DeliveryRepository deliveryRepository,
            OutboxEventRepository outboxEventRepository,
            WebhookEndpointRepository endpointRepository
    ) {
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.endpointRepository = endpointRepository;
    }

    @Transactional
    public EventResponse create(CreateEventRequest request) {
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

        return new EventResponse(
                event.getId(),
                event.getEventType(),
                event.getPayload(),
                deliveries.stream().map(Delivery::getId).toList(),
                event.getCreatedAt()
        );
    }
}
