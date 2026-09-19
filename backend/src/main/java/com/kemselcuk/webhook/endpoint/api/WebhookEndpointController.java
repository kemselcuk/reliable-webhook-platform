package com.kemselcuk.webhook.endpoint.api;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/webhook-endpoints")
public class WebhookEndpointController {

    private final WebhookEndpointService endpointService;

    public WebhookEndpointController(WebhookEndpointService endpointService) {
        this.endpointService = endpointService;
    }

    @PostMapping
    public ResponseEntity<WebhookEndpointResponse> create(
            @Valid @RequestBody CreateWebhookEndpointRequest request
    ) {
        WebhookEndpointResponse response = endpointService.create(request);
        return ResponseEntity.created(URI.create("/api/webhook-endpoints/" + response.id()))
                .body(response);
    }

    @GetMapping
    public WebhookEndpointPageResponse list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return endpointService.list(page, size);
    }

    @PatchMapping("/{endpointId}/enabled")
    public WebhookEndpointResponse setEnabled(
            @PathVariable UUID endpointId,
            @Valid @RequestBody SetWebhookEndpointEnabledRequest request
    ) {
        return endpointService.setEnabled(endpointId, request.enabled());
    }
}
