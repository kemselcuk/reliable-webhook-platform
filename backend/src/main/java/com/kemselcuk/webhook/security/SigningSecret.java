package com.kemselcuk.webhook.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Redaction-safe immutable secret material used for outbound webhook signing.
 * The bytes are copied on construction and access so callers cannot mutate a
 * snapshot retained by a worker or persistence entity.
 */
public final class SigningSecret {

    public static final int MIN_BYTES = 32;
    public static final int MAX_BYTES = 512;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] bytes;

    private SigningSecret(byte[] bytes) {
        this.bytes = validate(bytes);
    }

    public static SigningSecret fromText(String value) {
        Objects.requireNonNull(value, "secret");
        return new SigningSecret(value.getBytes(StandardCharsets.UTF_8));
    }

    public static SigningSecret fromBytes(byte[] value) {
        return new SigningSecret(value);
    }

    /**
     * Generates material for internally-created domain fixtures and migration
     * compatible persistence paths. REST callers must supply their own value.
     */
    public static SigningSecret generate() {
        byte[] generated = new byte[MIN_BYTES];
        RANDOM.nextBytes(generated);
        return new SigningSecret(generated);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "[REDACTED_SIGNING_SECRET]";
    }

    private static byte[] validate(byte[] value) {
        Objects.requireNonNull(value, "secret");
        if (value.length < MIN_BYTES || value.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "secret must contain between " + MIN_BYTES + " and " + MAX_BYTES
                            + " UTF-8 bytes"
            );
        }
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SigningSecret secret
                && Arrays.equals(bytes, secret.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
