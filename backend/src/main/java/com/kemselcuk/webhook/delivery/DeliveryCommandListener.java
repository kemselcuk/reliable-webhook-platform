package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.observability.LogContext;
import com.kemselcuk.webhook.observability.WebhookMetrics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/**
 * Kafka boundary for compact delivery commands. Only bounded poison-command
 * categories are handled here; infrastructure failures are allowed to reach
 * the container error handler for unbounded redelivery.
 */
@Component
@ConditionalOnProperty(
        prefix = "webhook.delivery.worker",
        name = "enabled",
        havingValue = "true"
)
public class DeliveryCommandListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeliveryCommandListener.class);

    private final DeliveryCommandParser commandParser;
    private final DeliveryWorker worker;
    private final DeliveryWorkerProperties properties;
    private final WebhookMetrics metrics;

    public DeliveryCommandListener(
            DeliveryCommandParser commandParser,
            DeliveryWorker worker,
            DeliveryWorkerProperties properties
    ) {
        this(commandParser, worker, properties, WebhookMetrics.noop());
    }

    @Autowired
    public DeliveryCommandListener(
            DeliveryCommandParser commandParser,
            DeliveryWorker worker,
            DeliveryWorkerProperties properties,
            WebhookMetrics metrics
    ) {
        this.commandParser = Objects.requireNonNull(commandParser, "commandParser");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @KafkaListener(
            topics = "${webhook.outbox.publisher.topic}",
            groupId = "${webhook.delivery.worker.group-id}",
            containerFactory = "deliveryKafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(acknowledgment, "acknowledgment");
        DeliveryCommand command;
        try {
            command = commandParser.parse(record.key(), record.value());
        } catch (DeliveryCommandParseException exception) {
            LOGGER.warn(
                    "discarding delivery command topic={} partition={} offset={} category={}",
                    record.topic(), record.partition(), record.offset(), exception.category()
            );
            metrics.recordKafkaCommand("discarded");
            acknowledgment.acknowledge();
            return;
        }

        try (LogContext ignored = LogContext.delivery(command.deliveryId())) {
            DeliveryWorkerResult result = Objects.requireNonNull(
                    worker.process(command.deliveryId()), "worker result"
            );
            if (result.disposition() == DeliveryWorkerDisposition.BUSY) {
                metrics.recordKafkaCommand("busy");
                Duration delay = properties.getBusyRedeliveryDelay();
                acknowledgment.nack(delay);
                return;
            }
            metrics.recordKafkaCommand("processed");
            acknowledgment.acknowledge();
        }
    }
}
