package com.kemselcuk.webhook.delivery;

import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/** Production jitter source with no shared mutable random state. */
@Component
public class ThreadLocalRetryJitterSource implements RetryJitterSource {

    @Override
    public double nextUnitDouble() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
