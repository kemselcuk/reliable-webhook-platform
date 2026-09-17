package com.kemselcuk.webhook.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void failedSendRequeuesWithBoundedCategoryAndLaterSuccessPublishes() throws Exception {
        OutboxClaim claim = claim(UUID.randomUUID());
        RecordingClaimOperations operations = new RecordingClaimOperations();
        operations.batches.add(List.of(claim));
        operations.batches.add(List.of(claim));
        RecordingCommandSender sender = new RecordingCommandSender();
        sender.outcomes.add(new TimeoutException("broker detail must not be stored"));
        OutboxPublisher publisher = publisher(operations, sender);

        assertThat(publisher.publishSingleCycle()).isZero();
        assertThat(operations.releases).hasSize(1);
        assertThat(operations.releases.getFirst().now()).isEqualTo(NOW);
        assertThat(operations.releases.getFirst().availableAt()).isEqualTo(NOW.plus(RETRY_DELAY));
        assertThat(operations.releases.getFirst().failureCategory()).isEqualTo("TIMEOUT");
        assertThat(operations.marked).isEmpty();

        assertThat(publisher.publishSingleCycle()).isEqualTo(1);
        assertThat(operations.marked).containsExactly(claim);
        assertThat(sender.sent).hasSize(2);
    }

    @Test
    void successfulSendBeforeDatabaseMarkCanBeRepublishedAfterLeaseRecovery() throws Exception {
        OutboxClaim firstClaim = claim(UUID.randomUUID());
        OutboxClaim replacementClaim = new OutboxClaim(
                firstClaim.id(),
                firstClaim.deliveryId(),
                firstClaim.payload(),
                UUID.randomUUID()
        );
        RecordingClaimOperations operations = new RecordingClaimOperations();
        operations.batches.add(List.of(firstClaim));
        operations.batches.add(List.of(replacementClaim));
        operations.markResults.add(false); // Represent a crash before the first mark commit.
        operations.markResults.add(true);
        RecordingCommandSender sender = new RecordingCommandSender();
        OutboxPublisher publisher = publisher(operations, sender);

        assertThat(publisher.publishSingleCycle()).isZero();
        assertThat(publisher.publishSingleCycle()).isEqualTo(1);

        assertThat(sender.sent).hasSize(2);
        assertThat(sender.sent.get(0).key()).isEqualTo(sender.sent.get(1).key());
        assertThat(sender.sent.get(0).value()).isEqualTo(sender.sent.get(1).value());
        assertThat(operations.marked).containsExactly(firstClaim, replacementClaim);
    }

    private OutboxPublisher publisher(
            RecordingClaimOperations operations,
            RecordingCommandSender sender
    ) {
        OutboxPublisherProperties properties = new OutboxPublisherProperties();
        properties.setEnabled(true);
        properties.setBatchSize(10);
        properties.setRetryDelay(RETRY_DELAY);
        properties.setSendTimeout(Duration.ofSeconds(2));
        return new OutboxPublisher(
                operations,
                sender,
                properties,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private OutboxClaim claim(UUID deliveryId) throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"version":1,"deliveryId":"%s"}
                """.formatted(deliveryId));
        return new OutboxClaim(UUID.randomUUID(), deliveryId, payload, UUID.randomUUID());
    }

    private record Sent(String topic, String key, String value, Duration timeout) {
    }

    private record Release(OutboxClaim claim, Instant now, Instant availableAt, String failureCategory) {
    }

    private static final class RecordingCommandSender implements OutboxCommandSender {
        private final Deque<Exception> outcomes = new ArrayDeque<>();
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(String topic, String key, String value, Duration timeout)
                throws InterruptedException, ExecutionException, TimeoutException {
            sent.add(new Sent(topic, key, value, timeout));
            Exception outcome = outcomes.pollFirst();
            if (outcome instanceof TimeoutException timeoutException) {
                throw timeoutException;
            }
            if (outcome instanceof InterruptedException interruptedException) {
                throw interruptedException;
            }
            if (outcome instanceof ExecutionException executionException) {
                throw executionException;
            }
        }
    }

    private static final class RecordingClaimOperations implements OutboxClaimOperations {
        private final Deque<List<OutboxClaim>> batches = new ArrayDeque<>();
        private final Deque<Boolean> markResults = new ArrayDeque<>();
        private final List<OutboxClaim> marked = new ArrayList<>();
        private final List<Release> releases = new ArrayList<>();

        @Override
        public List<OutboxClaim> claimBatch(int batchSize, Instant now, Duration claimTimeout) {
            return batches.isEmpty() ? List.of() : batches.removeFirst();
        }

        @Override
        public boolean markPublished(OutboxClaim claim, Instant publishedAt) {
            marked.add(claim);
            return markResults.isEmpty() || markResults.removeFirst();
        }

        @Override
        public boolean releaseForRetry(
                OutboxClaim claim,
                Instant now,
                Instant availableAt,
                String failureCategory
        ) {
            releases.add(new Release(claim, now, availableAt, failureCategory));
            return true;
        }
    }
}
