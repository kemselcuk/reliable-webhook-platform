package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.Options;
import com.kemselcuk.webhook.security.SigningSecret;
import com.kemselcuk.webhook.security.WebhookSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class RestClientDeliveryHttpClientIT {

    private WireMockServer wireMock;
    private RestClientDeliveryHttpClient client;
    private static final Instant SIGNING_TIME = Instant.parse("2026-09-19T10:15:30Z");
    private static final SigningSecret SECRET = SigningSecret.fromText("s".repeat(32));

    @BeforeEach
    void startServer() {
        wireMock = new WireMockServer(Options.DYNAMIC_PORT);
        wireMock.start();
        client = new RestClientDeliveryHttpClient(
                restClient(Duration.ofSeconds(2)), Clock.fixed(SIGNING_TIME, ZoneOffset.UTC)
        );
    }

    @AfterEach
    void stopServer() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @Test
    void postsExactJsonAndStableHeadersAndReturnsTwoHundredStatus() throws Exception {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/hooks"))
                .willReturn(aResponse().withStatus(204).withHeader("Retry-After", " 7 ")));
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        DeliveryWorkSnapshot work = work(eventId, deliveryId, wireMock.baseUrl() + "/hooks");

        DeliveryHttpResult result = client.post(work);

        assertThat(result.httpStatus()).isEqualTo(204);
        assertThat(result.transportFailure()).isNull();
        assertThat(result.retryAfter()).isEqualTo("7");
        wireMock.verify(postRequestedFor(urlEqualTo("/hooks"))
                .withRequestBody(equalTo("{\"orderId\":\"order-123\",\"items\":[1,2]}"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Accept", equalTo("application/json"))
                .withHeader("X-Webhook-Id", equalTo(eventId.toString()))
                .withHeader("X-Delivery-Id", equalTo(deliveryId.toString()))
                .withHeader("X-Webhook-Event", equalTo("order.created"))
                .withHeader("X-Webhook-Timestamp", equalTo("1789812930"))
                .withHeader("X-Webhook-Signature", equalTo(WebhookSignature.sign(
                        SECRET,
                        SIGNING_TIME,
                        "{\"orderId\":\"order-123\",\"items\":[1,2]}"
                                .getBytes(StandardCharsets.UTF_8)
                )))
                .withHeader("X-Webhook-Key-Id", equalTo("v1")));
    }

    @Test
    void returnsNonTwoHundredStatusWithoutThrowingOrRetainingBody() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/failure"))
                .willReturn(aResponse().withStatus(418).withBody("secret response body")));
        DeliveryWorkSnapshot work = work(UUID.randomUUID(), UUID.randomUUID(),
                wireMock.baseUrl() + "/failure");

        DeliveryHttpResult result = client.post(work);
        assertThat(result.httpStatus()).isEqualTo(418);
        assertThat(result.transportFailure()).isNull();
        assertThat(result.retryAfter()).isNull();
    }

    @Test
    void discardsOverlongRetryAfterHeader() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/overlong"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "x".repeat(129))));
        DeliveryWorkSnapshot work = work(UUID.randomUUID(), UUID.randomUUID(),
                wireMock.baseUrl() + "/overlong");

        DeliveryHttpResult result = client.post(work);

        assertThat(result.httpStatus()).isEqualTo(429);
        assertThat(result.retryAfter()).isNull();
    }

    @Test
    void responseTimeoutIsBoundedAndClassified() {
        Duration timeout = Duration.ofMillis(150);
        RestClientDeliveryHttpClient timeoutClient = new RestClientDeliveryHttpClient(
                restClient(timeout), Clock.fixed(SIGNING_TIME, ZoneOffset.UTC)
        );
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/slow"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1_000)));
        DeliveryWorkSnapshot work = work(UUID.randomUUID(), UUID.randomUUID(),
                wireMock.baseUrl() + "/slow");

        long started = System.nanoTime();
        DeliveryHttpResult result = timeoutClient.post(work);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.httpStatus()).isNull();
        assertThat(result.transportFailure()).isEqualTo(DeliveryTransportFailure.TIMEOUT);
        assertThat(elapsedMillis).isLessThan(2_000);
    }

    private RestClient restClient(Duration responseTimeout) {
        DeliveryWorkerProperties properties = new DeliveryWorkerProperties();
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setResponseTimeout(responseTimeout);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(responseTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private DeliveryWorkSnapshot work(UUID eventId, UUID deliveryId, String url) {
        try {
            return new DeliveryWorkSnapshot(
                    deliveryId,
                    eventId,
                    UUID.randomUUID(),
                    "order.created",
                    new ObjectMapper().readTree("{\"orderId\":\"order-123\",\"items\":[1,2]}"),
                    url,
                    true,
                    UUID.randomUUID(),
                    1,
                    1,
                    "v1",
                    SECRET
            );
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
