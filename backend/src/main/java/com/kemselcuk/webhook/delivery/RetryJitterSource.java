package com.kemselcuk.webhook.delivery;

/** Supplies a retry-jitter sample; policy code validates its [0, 1] range. */
@FunctionalInterface
public interface RetryJitterSource {

    double nextUnitDouble();
}
