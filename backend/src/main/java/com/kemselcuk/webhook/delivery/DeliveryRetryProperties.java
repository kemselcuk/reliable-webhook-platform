package com.kemselcuk.webhook.delivery;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Validated, bounded inputs for the side-effect-free delivery retry policy. */
@Component("deliveryRetryProperties")
@Validated
@ConfigurationProperties(prefix = "webhook.delivery.retry")
public class DeliveryRetryProperties {

    @Min(1)
    @Max(100)
    private int maxAttempts = 5;

    @NotNull
    private Duration initialDelay = Duration.ofSeconds(1);

    @NotNull
    private Duration maxDelay = Duration.ofHours(1);

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double jitterFactor = 0.20;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        this.maxDelay = maxDelay;
    }

    public double getJitterFactor() {
        return jitterFactor;
    }

    public void setJitterFactor(double jitterFactor) {
        this.jitterFactor = jitterFactor;
    }

    @AssertTrue(message = "retry delays must be positive and max-delay must not be less than initial-delay")
    public boolean hasValidDelays() {
        return initialDelay != null
                && maxDelay != null
                && !initialDelay.isZero()
                && !initialDelay.isNegative()
                && maxDelay.compareTo(initialDelay) >= 0
                && Double.isFinite(jitterFactor);
    }
}
