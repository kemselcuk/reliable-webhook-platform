package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.domain.Delivery;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import com.kemselcuk.webhook.domain.repository.DeliveryAttemptRepository;
import com.kemselcuk.webhook.domain.repository.DeliveryRepository;
import com.kemselcuk.webhook.web.ApiRequestValidationException;
import com.kemselcuk.webhook.web.DeliveryNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DeliveryReadService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;

    public DeliveryReadService(
            DeliveryRepository deliveryRepository,
            DeliveryAttemptRepository attemptRepository
    ) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
    }

    @Transactional(readOnly = true)
    public DeliveryPageResponse list(int page, int requestedSize, DeliveryStatus status) {
        PageRequest pageable = pageRequest(page, requestedSize);
        Page<Delivery> deliveries = status == null
                ? deliveryRepository.findAll(pageable)
                : deliveryRepository.findByStatus(status, pageable);
        return new DeliveryPageResponse(
                deliveries.getContent().stream().map(DeliveryListItemResponse::from).toList(),
                deliveries.getNumber(),
                pageable.getPageSize(),
                deliveries.getTotalElements(),
                deliveries.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public DeliveryDetailResponse get(UUID deliveryId) {
        Delivery delivery = deliveryRepository.findById(deliveryId)
                .orElseThrow(DeliveryNotFoundException::new);
        return DeliveryDetailResponse.from(
                delivery,
                attemptRepository.findByDeliveryIdOrderByAttemptNumberAsc(deliveryId).stream()
                        .map(DeliveryAttemptResponse::from)
                        .toList()
        );
    }

    private static PageRequest pageRequest(int page, int requestedSize) {
        if (page < 0) {
            throw new ApiRequestValidationException("page", "page must not be negative");
        }
        if (requestedSize < 1) {
            throw new ApiRequestValidationException("size", "size must be at least 1");
        }
        int size = Math.min(requestedSize, MAX_PAGE_SIZE);
        return PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        );
    }
}
