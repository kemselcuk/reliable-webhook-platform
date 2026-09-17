package com.kemselcuk.webhook.delivery;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Bounded local worker settings. Kafka listener enablement is intentionally
 * separate and may consume these settings in a later increment.
 */
@Component("deliveryWorkerProperties")
@Validated
@ConfigurationProperties(prefix = "webhook.delivery.worker")
public class DeliveryWorkerProperties {

    private boolean enabled;

    @NotNull
    private Duration claimTimeout = Duration.ofMinutes(5);

    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(2);

    @NotNull
    private Duration responseTimeout = Duration.ofSeconds(10);

    @Min(1)
    @Max(100)
    private int concurrency = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getClaimTimeout() {
        return claimTimeout;
    }

    public void setClaimTimeout(Duration claimTimeout) {
        this.claimTimeout = claimTimeout;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getResponseTimeout() {
        return responseTimeout;
    }

    public void setResponseTimeout(Duration responseTimeout) {
        this.responseTimeout = responseTimeout;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    @jakarta.validation.constraints.AssertTrue(message = "worker durations must be positive")
    public boolean hasPositiveDurations() {
        return isPositive(claimTimeout)
                && isPositive(connectTimeout)
                && isPositive(responseTimeout);
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
