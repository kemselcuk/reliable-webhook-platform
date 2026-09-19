package com.kemselcuk.webhook.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.Metric;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Exposes one bounded aggregate gauge for the maximum active consumer lag. */
@Component
public class KafkaConsumerLagMetrics {

    private final KafkaListenerEndpointRegistry listenerRegistry;

    public KafkaConsumerLagMetrics(
            MeterRegistry meterRegistry,
            KafkaListenerEndpointRegistry listenerRegistry
    ) {
        this.listenerRegistry = listenerRegistry;
        Gauge.builder("webhook.kafka.consumer.lag", this, KafkaConsumerLagMetrics::currentLag)
                .description("Maximum records lag across active delivery consumers")
                .register(meterRegistry);
    }

    double currentLag() {
        double maximum = 0;
        for (MessageListenerContainer container : listenerRegistry.getListenerContainers()) {
            try {
                maximum = Math.max(maximum, container.metrics().values().stream()
                        .flatMap(metrics -> metrics.entrySet().stream())
                        .filter(entry -> "records-lag-max".equals(entry.getKey().name()))
                        .map(Map.Entry::getValue)
                        .mapToDouble(KafkaConsumerLagMetrics::metricValue)
                        .filter(value -> !Double.isNaN(value) && value >= 0)
                        .max()
                        .orElse(0));
            } catch (RuntimeException ignored) {
                // A consumer can rebalance while Prometheus scrapes. Keep the
                // aggregate safe and let the next scrape observe fresh values.
            }
        }
        return maximum;
    }

    private static double metricValue(Metric metric) {
        Object value = metric.metricValue();
        return value instanceof Number number ? number.doubleValue() : Double.NaN;
    }
}
