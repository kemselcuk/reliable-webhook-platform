package com.kemselcuk.webhook.delivery.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryReplayController {

    private final DeliveryReplayService replayService;

    public DeliveryReplayController(DeliveryReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/{deliveryId}/replay")
    public ResponseEntity<DeliveryReplayResponse> replay(@PathVariable UUID deliveryId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(replayService.replay(deliveryId));
    }
}
