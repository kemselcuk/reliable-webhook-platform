package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryReadController {

    private final DeliveryReadService deliveryReadService;

    public DeliveryReadController(DeliveryReadService deliveryReadService) {
        this.deliveryReadService = deliveryReadService;
    }

    @GetMapping
    public DeliveryPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) DeliveryStatus status
    ) {
        return deliveryReadService.list(page, size, status);
    }

    @GetMapping("/{deliveryId}")
    public DeliveryDetailResponse get(@PathVariable UUID deliveryId) {
        return deliveryReadService.get(deliveryId);
    }
}
