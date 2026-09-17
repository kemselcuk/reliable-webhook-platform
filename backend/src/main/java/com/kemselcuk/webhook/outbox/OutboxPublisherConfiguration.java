package com.kemselcuk.webhook.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class OutboxPublisherConfiguration {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock outboxPublisherClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnExpression(
            "${webhook.outbox.publisher.enabled:false} "
                    + "or ${webhook.delivery.worker.enabled:false}"
    )
    NewTopic deliveryCommandTopic(OutboxPublisherProperties properties) {
        return TopicBuilder.name(properties.getTopic())
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "webhook.outbox.publisher", name = "enabled", havingValue = "true")
    static class KafkaSchedulingConfiguration {
    }
}
