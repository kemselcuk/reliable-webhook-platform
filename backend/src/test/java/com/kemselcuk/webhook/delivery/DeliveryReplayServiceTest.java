package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.delivery.api.DeliveryReplayResponse;
import com.kemselcuk.webhook.delivery.api.DeliveryReplayService;
import com.kemselcuk.webhook.web.DeliveryNotFoundException;
import com.kemselcuk.webhook.web.DeliveryNotReplayableException;
import com.kemselcuk.webhook.web.DisabledEndpointException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");

    @Mock
    private DeliveryReplayStore replayStore;

    @Test
    void mapsReplayedStateToCompactResponseUsingInjectedClock() {
        UUID deliveryId = UUID.randomUUID();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(replayStore.replay(deliveryId, NOW))
                .thenReturn(DeliveryReplayResult.replayed(deliveryId, 4));
        DeliveryReplayService service = new DeliveryReplayService(replayStore, clock);

        DeliveryReplayResponse response = service.replay(deliveryId);

        assertThat(response.deliveryId()).isEqualTo(deliveryId);
        assertThat(response.status().name()).isEqualTo("PENDING");
        assertThat(response.attemptCount()).isEqualTo(4);
        assertThat(response.currentRunAttemptCount()).isZero();
        verify(replayStore).replay(deliveryId, NOW);
    }

    @Test
    void mapsPersistenceDispositionsToApiExceptions() {
        UUID deliveryId = UUID.randomUUID();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DeliveryReplayService service = new DeliveryReplayService(replayStore, clock);

        when(replayStore.replay(deliveryId, NOW)).thenReturn(DeliveryReplayResult.notFound());
        assertThatThrownBy(() -> service.replay(deliveryId))
                .isInstanceOf(DeliveryNotFoundException.class);

        when(replayStore.replay(deliveryId, NOW)).thenReturn(DeliveryReplayResult.disabled());
        assertThatThrownBy(() -> service.replay(deliveryId))
                .isInstanceOf(DisabledEndpointException.class);

        when(replayStore.replay(deliveryId, NOW)).thenReturn(DeliveryReplayResult.ineligible());
        assertThatThrownBy(() -> service.replay(deliveryId))
                .isInstanceOf(DeliveryNotReplayableException.class);
    }
}
