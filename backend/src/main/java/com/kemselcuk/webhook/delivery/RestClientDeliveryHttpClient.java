package com.kemselcuk.webhook.delivery;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Objects;

/**
 * JDK HTTP transport adapter. It closes the response stream immediately so a
 * response body cannot escape into worker state, logs, or metrics. Closing
 * without draining is an intentional safety bound for untrusted receivers.
 */
public class RestClientDeliveryHttpClient implements DeliveryHttpClient {

    private final RestClient restClient;

    public RestClientDeliveryHttpClient(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
    }

    @Override
    public DeliveryHttpResult post(DeliveryWorkSnapshot work) {
        Objects.requireNonNull(work, "work");
        try {
            return restClient.post()
                    .uri(work.endpointUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Webhook-Id", work.eventId().toString())
                    .header("X-Delivery-Id", work.deliveryId().toString())
                    .header("X-Webhook-Event", work.eventType())
                    .body(work.payload().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .exchange((request, response) -> {
                        discardBody(response.getBody());
                        return DeliveryHttpResult.httpStatus(
                                response.getStatusCode().value(),
                                sanitizedRetryAfter(response.getHeaders().getFirst("Retry-After"))
                        );
                    });
        } catch (ResourceAccessException exception) {
            return DeliveryHttpResult.transportFailure(classify(exception));
        } catch (RuntimeException exception) {
            // Invalid endpoint URLs and other local I/O adapter failures are
            // intentionally reduced to a bounded category without details.
            return DeliveryHttpResult.transportFailure(DeliveryTransportFailure.IO_FAILURE);
        }
    }

    private static void discardBody(InputStream body) throws IOException {
        if (body != null) {
            body.close();
        }
    }

    private static String sanitizedRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || trimmed.length() > 128 ? null : trimmed;
    }

    private static DeliveryTransportFailure classify(ResourceAccessException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return DeliveryTransportFailure.TIMEOUT;
            }
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return DeliveryTransportFailure.CONNECTION;
            }
            cause = cause.getCause();
        }
        return DeliveryTransportFailure.IO_FAILURE;
    }
}
