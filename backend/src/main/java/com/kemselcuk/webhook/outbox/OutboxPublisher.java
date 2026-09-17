package com.kemselcuk.webhook.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component("outboxPublisher")
@ConditionalOnProperty(prefix = "webhook.outbox.publisher", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private final OutboxClaimOperations claimOperations;
    private final OutboxCommandSender commandSender;
    private final OutboxPublisherProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPublisher(
            OutboxClaimOperations claimOperations,
            OutboxCommandSender commandSender,
            OutboxPublisherProperties properties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.claimOperations = claimOperations;
        this.commandSender = commandSender;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${webhook.outbox.publisher.poll-interval:PT1S}")
    public void publishScheduled() {
        publishSingleCycle();
    }

    public int publishSingleCycle() {
        Instant claimedAt = clock.instant();
        List<OutboxClaim> claims = claimOperations.claimBatch(
                properties.getBatchSize(), claimedAt, properties.getClaimTimeout()
        );
        int published = 0;
        for (int index = 0; index < claims.size(); index++) {
            OutboxClaim claim = claims.get(index);
            try {
                String value = objectMapper.writeValueAsString(claim.payload());
                commandSender.send(
                        properties.getTopic(),
                        claim.deliveryId().toString(),
                        value,
                        properties.getSendTimeout()
                );
                if (claimOperations.markPublished(claim, clock.instant())) {
                    published++;
                }
            } catch (InterruptedException exception) {
                releaseForRetry(claim, "INTERRUPTED");
                releaseRemaining(claims.subList(index + 1, claims.size()), "INTERRUPTED");
                Thread.currentThread().interrupt();
                break;
            } catch (TimeoutException exception) {
                releaseForRetry(claim, "TIMEOUT");
            } catch (ExecutionException exception) {
                releaseForRetry(claim, "KAFKA");
            } catch (JsonProcessingException exception) {
                releaseForRetry(claim, "SERIALIZATION");
            } catch (RuntimeException exception) {
                releaseForRetry(claim, "PUBLISH_FAILURE");
            }
        }
        return published;
    }

    private void releaseRemaining(List<OutboxClaim> claims, String category) {
        Instant now = clock.instant();
        Instant availableAt = now.plus(properties.getRetryDelay());
        claims.forEach(claim -> claimOperations.releaseForRetry(claim, now, availableAt, category));
    }

    private void releaseForRetry(OutboxClaim claim, String category) {
        Instant now = clock.instant();
        claimOperations.releaseForRetry(claim, now, now.plus(properties.getRetryDelay()), category);
    }
}
