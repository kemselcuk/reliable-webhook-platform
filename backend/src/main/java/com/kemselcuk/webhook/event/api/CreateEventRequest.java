package com.kemselcuk.webhook.event.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateEventRequest(
        @NotBlank(message = "type must not be blank")
        @Size(max = 255, message = "type must be at most 255 characters")
        String type,
        @NotNull(message = "payload must not be null")
        JsonNode payload,
        @NotNull(message = "endpointIds must not be null")
        @NotEmpty(message = "endpointIds must not be empty")
        @Size(max = 100, message = "endpointIds must contain at most 100 items")
        List<@NotNull(message = "endpointIds must not contain null values") UUID> endpointIds
) {
}
