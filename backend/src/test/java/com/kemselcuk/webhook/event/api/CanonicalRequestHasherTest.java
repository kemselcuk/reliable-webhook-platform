package com.kemselcuk.webhook.event.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRequestHasherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalRequestHasher hasher = new CanonicalRequestHasher(objectMapper);

    @Test
    void hashesEquivalentRequestRepresentationsTheSame() throws Exception {
        UUID firstEndpoint = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondEndpoint = UUID.fromString("00000000-0000-0000-0000-000000000002");

        CreateEventRequest first = new CreateEventRequest(
                " order.created ",
                objectMapper.readTree("{\"z\":2.0,\"nested\":{\"b\":true,\"a\":null},\"a\":1}"),
                List.of(firstEndpoint, secondEndpoint)
        );
        CreateEventRequest equivalent = new CreateEventRequest(
                "order.created",
                objectMapper.readTree("{\"a\":1.0,\"nested\":{\"a\":null,\"b\":true},\"z\":2}"),
                List.of(secondEndpoint, firstEndpoint)
        );

        assertThat(hasher.hash(first)).isEqualTo(hasher.hash(equivalent));
    }

    @Test
    void preservesArrayOrderBecauseArrayValuesAreOrdered() throws Exception {
        UUID endpoint = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateEventRequest first = new CreateEventRequest(
                "order.created",
                objectMapper.readTree("{\"items\":[1,2]}"),
                List.of(endpoint)
        );
        CreateEventRequest reorderedPayload = new CreateEventRequest(
                "order.created",
                objectMapper.readTree("{\"items\":[2,1]}"),
                List.of(endpoint)
        );

        assertThat(hasher.hash(first)).isNotEqualTo(hasher.hash(reorderedPayload));
    }
}
