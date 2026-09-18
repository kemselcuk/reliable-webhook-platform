package com.kemselcuk.webhook.delivery;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component("deliveryRetrySchedulerProperties")
@Validated
@ConfigurationProperties(prefix = "webhook.delivery.retry.scheduler")
public class DeliveryRetrySchedulerProperties {

    private boolean enabled;

    @NotNull
    private Duration pollInterval = Duration.ofSeconds(1);

    @Min(1)
    @Max(500)
    private int batchSize = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    @AssertTrue(message = "retry scheduler poll interval must be positive")
    public boolean hasPositivePollInterval() {
        return pollInterval != null && !pollInterval.isZero() && !pollInterval.isNegative();
    }
}
