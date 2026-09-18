package com.kemselcuk.webhook.event.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class CanonicalRequestHasher {

    private final ObjectMapper objectMapper;

    public CanonicalRequestHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(CreateEventRequest request) {
        ObjectNode canonicalRequest = JsonNodeFactory.instance.objectNode();
        canonicalRequest.put("type", request.type().trim());
        canonicalRequest.set("payload", canonicalize(request.payload()));

        ArrayNode endpointIds = canonicalRequest.putArray("endpointIds");
        request.endpointIds().stream()
                .map(UUID::toString)
                .sorted()
                .forEach(endpointIds::add);

        try {
            byte[] canonicalJson = objectMapper.writeValueAsBytes(canonicalRequest);
            return hex(MessageDigest.getInstance("SHA-256").digest(canonicalJson));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize canonical event request", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode canonical = JsonNodeFactory.instance.objectNode();
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
            node.fields().forEachRemaining(fields::add);
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            fields.forEach(field -> canonical.set(field.getKey(), canonicalize(field.getValue())));
            return canonical;
        }
        if (node.isArray()) {
            ArrayNode canonical = JsonNodeFactory.instance.arrayNode();
            node.elements().forEachRemaining(element -> canonical.add(canonicalize(element)));
            return canonical;
        }
        if (node.isNumber()) {
            BigDecimal number = node.decimalValue().stripTrailingZeros();
            return JsonNodeFactory.instance.numberNode(
                    number.signum() == 0 ? BigDecimal.ZERO : number
            );
        }
        return node.deepCopy();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
