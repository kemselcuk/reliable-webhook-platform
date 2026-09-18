package com.kemselcuk.webhook.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

@Component
@ConditionalOnProperty(
        prefix = "webhook.delivery.retry.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class DeliveryRetryScheduler {

    private final DeliveryRetryRequeueStore requeueStore;
    private final DeliveryRetrySchedulerProperties properties;
    private final Clock clock;

    public DeliveryRetryScheduler(
            DeliveryRetryRequeueStore requeueStore,
            DeliveryRetrySchedulerProperties properties,
            Clock clock
    ) {
        this.requeueStore = Objects.requireNonNull(requeueStore, "requeueStore");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Scheduled(fixedDelayString = "${webhook.delivery.retry.scheduler.poll-interval:PT1S}")
    public void requeueDueScheduled() {
        requeueDueOnce();
    }

    public int requeueDueOnce() {
        return requeueStore.requeueDue(properties.getBatchSize(), clock.instant());
    }
}
