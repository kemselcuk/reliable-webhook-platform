package com.kemselcuk.webhook.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@Component("outboxPublisherProperties")
@ConfigurationProperties(prefix = "webhook.outbox.publisher")
public class OutboxPublisherProperties {

    @NotBlank
    private String topic = "webhook.delivery.commands.v1";

    private boolean enabled;

    @Min(1)
    @Max(500)
    private int batchSize = 50;

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(1);

    @NotNull
    private Duration claimTimeout = Duration.ofMinutes(5);

    @NotNull
    private Duration sendTimeout = Duration.ofSeconds(10);

    @NotNull
    private Duration retryDelay = Duration.ofSeconds(5);

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getClaimTimeout() {
        return claimTimeout;
    }

    public void setClaimTimeout(Duration claimTimeout) {
        this.claimTimeout = claimTimeout;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }

    @AssertTrue(message = "publisher durations must be positive")
    public boolean hasPositiveDurations() {
        return isPositive(pollInterval)
                && isPositive(claimTimeout)
                && isPositive(sendTimeout)
                && isPositive(retryDelay);
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
