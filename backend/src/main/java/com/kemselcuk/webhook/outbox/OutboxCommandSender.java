package com.kemselcuk.webhook.outbox;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@FunctionalInterface
public interface OutboxCommandSender {

    void send(String topic, String key, String value, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException;
}
