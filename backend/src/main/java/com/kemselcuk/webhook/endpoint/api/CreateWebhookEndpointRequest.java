package com.kemselcuk.webhook.endpoint.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebhookEndpointRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,
        @NotBlank(message = "url must not be blank")
        @Size(max = 2048, message = "url must be at most 2048 characters")
        String url,
        @NotBlank(message = "secret must not be blank")
        @Size(max = 512, message = "secret must be at most 512 characters")
        String secret
) {
}
