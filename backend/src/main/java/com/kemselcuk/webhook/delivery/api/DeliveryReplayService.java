package com.kemselcuk.webhook.delivery.api;

import com.kemselcuk.webhook.delivery.DeliveryReplayResult;
import com.kemselcuk.webhook.delivery.DeliveryReplayStore;
import com.kemselcuk.webhook.web.DeliveryNotFoundException;
import com.kemselcuk.webhook.web.DeliveryNotReplayableException;
import com.kemselcuk.webhook.web.DisabledEndpointException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
public class DeliveryReplayService {

    private final DeliveryReplayStore replayStore;
    private final Clock clock;

    public DeliveryReplayService(DeliveryReplayStore replayStore, Clock clock) {
        this.replayStore = Objects.requireNonNull(replayStore, "replayStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeliveryReplayResponse replay(UUID deliveryId) {
        DeliveryReplayResult result = replayStore.replay(deliveryId, clock.instant());
        return switch (result.disposition()) {
            case REPLAYED -> DeliveryReplayResponse.from(result);
            case NOT_FOUND -> throw new DeliveryNotFoundException();
            case DISABLED -> throw new DisabledEndpointException();
            case INELIGIBLE -> throw new DeliveryNotReplayableException();
        };
    }
}
