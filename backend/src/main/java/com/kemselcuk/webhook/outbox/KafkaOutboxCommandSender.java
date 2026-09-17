package com.kemselcuk.webhook.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(prefix = "webhook.outbox.publisher", name = "enabled", havingValue = "true")
public class KafkaOutboxCommandSender implements OutboxCommandSender {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaOutboxCommandSender(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void send(String topic, String key, String value, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        kafkaTemplate.send(topic, key, value).get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
