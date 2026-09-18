package com.kemselcuk.webhook.delivery;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryRetrySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Test
    void singleCycleUsesConfiguredBatchAndInjectedClock() {
        DeliveryRetryRequeueStore store = mock(DeliveryRetryRequeueStore.class);
        DeliveryRetrySchedulerProperties properties = new DeliveryRetrySchedulerProperties();
        properties.setBatchSize(7);
        when(store.requeueDue(7, NOW)).thenReturn(3);
        DeliveryRetryScheduler scheduler = new DeliveryRetryScheduler(
                store,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(scheduler.requeueDueOnce()).isEqualTo(3);
        verify(store).requeueDue(7, NOW);
    }
}
