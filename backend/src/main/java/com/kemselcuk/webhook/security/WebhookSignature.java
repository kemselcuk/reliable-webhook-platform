package com.kemselcuk.webhook.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Versioned HMAC-SHA256 signing contract for outbound webhook requests. */
public final class WebhookSignature {

    public static final String VERSION = "v1";
    public static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {
    }

    public static String sign(SigningSecret secret, Instant timestamp, byte[] rawBody) {
        if (secret == null || timestamp == null || rawBody == null) {
            throw new IllegalArgumentException("secret, timestamp, and rawBody are required");
        }
        long epochSeconds = timestamp.getEpochSecond();
        if (epochSeconds < 0) {
            throw new IllegalArgumentException("timestamp must not be before the Unix epoch");
        }
        byte[] timestampBytes = Long.toString(epochSeconds).getBytes(StandardCharsets.US_ASCII);
        byte[] signingInput = new byte[timestampBytes.length + 1 + rawBody.length];
        System.arraycopy(timestampBytes, 0, signingInput, 0, timestampBytes.length);
        signingInput[timestampBytes.length] = (byte) '.';
        System.arraycopy(rawBody, 0, signingInput, timestampBytes.length + 1, rawBody.length);

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.bytes(), ALGORITHM));
            return VERSION + "=" + HexFormat.of().formatHex(mac.doFinal(signingInput));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    public static String timestampHeaderValue(Instant timestamp) {
        if (timestamp == null || timestamp.getEpochSecond() < 0) {
            throw new IllegalArgumentException("timestamp must be a non-negative Unix time");
        }
        return Long.toString(timestamp.getEpochSecond());
    }
}
