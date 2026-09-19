package com.kemselcuk.webhook.endpoint.api;

import com.kemselcuk.webhook.domain.WebhookEndpoint;
import com.kemselcuk.webhook.security.SigningSecret;
import com.kemselcuk.webhook.domain.repository.WebhookEndpointRepository;
import com.kemselcuk.webhook.web.ApiRequestValidationException;
import com.kemselcuk.webhook.web.EndpointNameConflictException;
import com.kemselcuk.webhook.web.EndpointNotFoundException;
import com.kemselcuk.webhook.web.InvalidEndpointUrlException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.UUID;

@Service
public class WebhookEndpointService {

    private static final int MAX_PAGE_SIZE = 100;

    private final WebhookEndpointRepository endpointRepository;

    public WebhookEndpointService(WebhookEndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    @Transactional
    public WebhookEndpointResponse create(CreateWebhookEndpointRequest request) {
        String name = request.name().trim();
        String normalizedUrl = normalizeUrl(request.url());
        SigningSecret secret = parseSecret(request.secret());

        if (endpointRepository.existsByName(name)) {
            throw new EndpointNameConflictException();
        }

        try {
            WebhookEndpoint endpoint = endpointRepository.saveAndFlush(
                    WebhookEndpoint.create(name, normalizedUrl, secret)
            );
            return toResponse(endpoint);
        } catch (DataIntegrityViolationException exception) {
            // The database unique constraint closes the concurrent-create race.
            throw new EndpointNameConflictException();
        }
    }

    private static SigningSecret parseSecret(String value) {
        if (value == null) {
            throw new ApiRequestValidationException(
                    "secret", "must contain between " + SigningSecret.MIN_BYTES
                            + " and " + SigningSecret.MAX_BYTES + " UTF-8 bytes"
            );
        }
        try {
            return SigningSecret.fromText(value);
        } catch (IllegalArgumentException exception) {
            throw new ApiRequestValidationException(
                    "secret", "must contain between " + SigningSecret.MIN_BYTES
                            + " and " + SigningSecret.MAX_BYTES + " UTF-8 bytes"
            );
        }
    }

    @Transactional(readOnly = true)
    public WebhookEndpointPageResponse list(int page, int requestedSize) {
        if (page < 0) {
            throw new ApiRequestValidationException("page", "page must not be negative");
        }
        if (requestedSize < 1) {
            throw new ApiRequestValidationException("size", "size must be at least 1");
        }

        int size = Math.min(requestedSize, MAX_PAGE_SIZE);
        Page<WebhookEndpoint> endpointPage = endpointRepository.findAll(PageRequest.of(
                page,
                size,
                Sort.by(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                )
        ));

        return new WebhookEndpointPageResponse(
                endpointPage.getContent().stream().map(WebhookEndpointService::toResponse).toList(),
                endpointPage.getNumber(),
                size,
                endpointPage.getTotalElements(),
                endpointPage.getTotalPages()
        );
    }

    @Transactional
    public WebhookEndpointResponse setEnabled(UUID endpointId, Boolean enabled) {
        WebhookEndpoint endpoint = endpointRepository.findById(endpointId)
                .orElseThrow(EndpointNotFoundException::new);
        if (Boolean.TRUE.equals(enabled)) {
            endpoint.enable();
        } else {
            endpoint.disable();
        }
        endpointRepository.flush();
        return toResponse(endpoint);
    }

    private static WebhookEndpointResponse toResponse(WebhookEndpoint endpoint) {
        return new WebhookEndpointResponse(
                endpoint.getId(),
                endpoint.getName(),
                endpoint.getUrl(),
                endpoint.isEnabled(),
                endpoint.getCreatedAt(),
                endpoint.getUpdatedAt()
        );
    }

    private static String normalizeUrl(String value) {
        try {
            URI parsed = URI.create(value.trim());
            String scheme = parsed.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("https"))) {
                throw new InvalidEndpointUrlException();
            }
            if (parsed.getHost() == null || parsed.getHost().isBlank()
                    || parsed.getRawFragment() != null || parsed.getRawUserInfo() != null) {
                throw new InvalidEndpointUrlException();
            }
            return parsed.normalize().toString();
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidEndpointUrlException();
        }
    }
}
