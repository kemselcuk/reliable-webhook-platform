package com.kemselcuk.webhook.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.delivery.DeliveryWorkSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureTest {

    private static final Instant TIMESTAMP = Instant.ofEpochSecond(1_789_812_930L);
    private static final SigningSecret SECRET = SigningSecret.fromText(
            "ssssssssssssssssssssssssssssssss"
    );

    @Test
    void signsTimestampDotRawBodyWithVersionedLowercaseHex() {
        byte[] body = "{\"orderId\":\"order-123\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, TIMESTAMP, body))
                .isEqualTo("v1=a11b0a62c9dd5041efd6043b6d6698d80bc4ef55cf8a41256565264a73333c8f");
    }

    @Test
    void changingBodyOrTimestampChangesTheSignature() {
        byte[] body = "{\"orderId\":\"order-123\"}".getBytes(StandardCharsets.UTF_8);
        String original = WebhookSignature.sign(SECRET, TIMESTAMP, body);

        assertThat(WebhookSignature.sign(
                SECRET, TIMESTAMP, "{\"orderId\":\"order-124\"}".getBytes(StandardCharsets.UTF_8)
        )).isNotEqualTo(original);
        assertThat(WebhookSignature.sign(SECRET, TIMESTAMP.plusSeconds(1), body))
                .isNotEqualTo(original);
    }

    @Test
    void secretIsDefensivelyCopiedAndRedacted() {
        byte[] raw = "ssssssssssssssssssssssssssssssss".getBytes(StandardCharsets.UTF_8);
        SigningSecret secret = SigningSecret.fromBytes(raw);
        raw[0] = 'x';
        byte[] exposedCopy = secret.bytes();
        exposedCopy[0] = 'x';

        assertThat(secret.bytes()[0]).isEqualTo((byte) 's');
        assertThat(secret.toString()).doesNotContain("ssss");
    }

    @Test
    void deliverySnapshotDiagnosticsDoNotContainPayloadOrSecret() throws Exception {
        DeliveryWorkSnapshot snapshot = new DeliveryWorkSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "order.created",
                new ObjectMapper().readTree("{\"secretPayload\":\"do-not-log\"}"),
                "https://receiver.example.test/hooks",
                true,
                UUID.randomUUID(),
                1,
                1,
                "v1",
                SECRET
        );

        assertThat(snapshot.toString()).doesNotContain("secretPayload", "do-not-log", "ssss");
    }

    @Test
    void validatesUtf8ByteBounds() {
        assertThatThrownBy(() -> SigningSecret.fromText("s".repeat(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningSecret.fromText("é".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(SigningSecret.fromText("é".repeat(16)).bytes()).hasSize(32);
    }
}
