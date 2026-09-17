package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryCommandParserTest {

    private static final UUID DELIVERY_ID = UUID.fromString(
            "f1f4b2cd-6f96-4b6a-b7df-1dbd3be4e9bc"
    );

    private final DeliveryCommandParser parser = new DeliveryCommandParser(new ObjectMapper());

    @Test
    void parsesOnlyVersionOneWithMatchingKafkaKey() {
        DeliveryCommand command = parser.parse(
                DELIVERY_ID.toString(),
                "{\"version\":1,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID)
        );

        assertThat(command.version()).isEqualTo(1);
        assertThat(command.deliveryId()).isEqualTo(DELIVERY_ID);
    }

    @Test
    void rejectsMalformedCommandsWithoutRetainingValue() {
        assertCategory("not-json", DELIVERY_ID.toString(), DeliveryCommandErrorCategory.MALFORMED);
        assertCategory(
                "{\"version\":1,\"deliveryId\":\"%s\"} trailing".formatted(DELIVERY_ID),
                DELIVERY_ID.toString(),
                DeliveryCommandErrorCategory.MALFORMED
        );
        assertCategory(
                "{\"version\":1,\"deliveryId\":\"%s\",\"extra\":true}".formatted(DELIVERY_ID),
                DELIVERY_ID.toString(),
                DeliveryCommandErrorCategory.MALFORMED
        );
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertCategory(
                "{\"version\":2,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID),
                DELIVERY_ID.toString(),
                DeliveryCommandErrorCategory.UNSUPPORTED_VERSION
        );
    }

    @Test
    void rejectsInvalidDeliveryIdAndKeyMismatch() {
        assertCategory(
                "{\"version\":1,\"deliveryId\":\"not-a-uuid\"}",
                "not-a-uuid",
                DeliveryCommandErrorCategory.INVALID_DELIVERY_ID
        );
        assertCategory(
                "{\"version\":1,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID),
                UUID.randomUUID().toString(),
                DeliveryCommandErrorCategory.KEY_MISMATCH
        );
        assertCategory(
                "{\"version\":1,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID),
                null,
                DeliveryCommandErrorCategory.KEY_MISMATCH
        );
    }

    @Test
    void rejectsDuplicateFields() {
        assertCategory(
                "{\"version\":1,\"version\":1,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID),
                DELIVERY_ID.toString(),
                DeliveryCommandErrorCategory.MALFORMED
        );
    }

    private void assertCategory(
            String value,
            String key,
            DeliveryCommandErrorCategory expected
    ) {
        assertThatThrownBy(() -> parser.parse(key, value))
                .isInstanceOf(DeliveryCommandParseException.class)
                .satisfies(exception -> assertThat(((DeliveryCommandParseException) exception).category())
                        .isEqualTo(expected))
                .hasMessage(expected.name());
    }
}
