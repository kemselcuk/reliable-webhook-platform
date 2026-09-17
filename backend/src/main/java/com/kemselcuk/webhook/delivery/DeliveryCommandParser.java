package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Strict parser for the compact v1 delivery command. It validates the Kafka
 * key against the command identity before the worker is invoked.
 */
@Component
public class DeliveryCommandParser {

    private static final Set<String> REQUIRED_FIELDS = Set.of("version", "deliveryId");

    private final ObjectMapper objectMapper;

    public DeliveryCommandParser(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public DeliveryCommand parse(String key, String value) {
        if (value == null || value.isBlank()) {
            throw malformed();
        }

        JsonNode command;
        try {
            command = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(value);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw malformed();
        }
        if (command == null || !command.isObject() || command.size() != REQUIRED_FIELDS.size()
                || !fieldNamesMatch(command)) {
            throw malformed();
        }

        JsonNode versionNode = command.get("version");
        if (versionNode == null || !versionNode.isIntegralNumber()
                || !versionNode.canConvertToInt()) {
            throw malformed();
        }
        int version = versionNode.intValue();
        if (version != 1) {
            throw new DeliveryCommandParseException(DeliveryCommandErrorCategory.UNSUPPORTED_VERSION);
        }

        JsonNode deliveryIdNode = command.get("deliveryId");
        if (deliveryIdNode == null || !deliveryIdNode.isTextual()) {
            throw new DeliveryCommandParseException(DeliveryCommandErrorCategory.INVALID_DELIVERY_ID);
        }
        String deliveryIdValue = deliveryIdNode.textValue();
        UUID deliveryId;
        try {
            deliveryId = UUID.fromString(deliveryIdValue);
        } catch (IllegalArgumentException exception) {
            throw new DeliveryCommandParseException(DeliveryCommandErrorCategory.INVALID_DELIVERY_ID);
        }
        if (!deliveryId.toString().equals(deliveryIdValue)) {
            throw new DeliveryCommandParseException(DeliveryCommandErrorCategory.INVALID_DELIVERY_ID);
        }
        if (key == null || !key.equals(deliveryId.toString())) {
            throw new DeliveryCommandParseException(DeliveryCommandErrorCategory.KEY_MISMATCH);
        }
        return new DeliveryCommand(version, deliveryId);
    }

    private static boolean fieldNamesMatch(JsonNode command) {
        Set<String> fields = new HashSet<>();
        Iterator<String> names = command.fieldNames();
        names.forEachRemaining(fields::add);
        return fields.equals(REQUIRED_FIELDS);
    }

    private static DeliveryCommandParseException malformed() {
        return new DeliveryCommandParseException(DeliveryCommandErrorCategory.MALFORMED);
    }
}
