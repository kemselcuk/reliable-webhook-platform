package com.kemselcuk.webhook.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaConsumerLagMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void exposesMaximumRecordsLagAsAnUnlabelledAggregate() {
        KafkaListenerEndpointRegistry listenerRegistry = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        Metric metric = mock(Metric.class);
        MetricName lagName = new MetricName(
                "records-lag-max", "consumer-fetch-manager-metrics", "", Map.of()
        );
        when(metric.metricValue()).thenReturn(7.0);
        when(container.metrics()).thenReturn((Map) Map.of(
                "consumer-1", Map.of(lagName, metric)
        ));
        when(listenerRegistry.getListenerContainers()).thenReturn(List.of(container));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new KafkaConsumerLagMetrics(meterRegistry, listenerRegistry);

        assertThat(meterRegistry.get("webhook.kafka.consumer.lag").gauge().value())
                .isEqualTo(7.0);
        assertThat(meterRegistry.get("webhook.kafka.consumer.lag").gauge().getId().getTags())
                .isEmpty();
    }
}
