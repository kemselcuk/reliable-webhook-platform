package com.kemselcuk.webhook.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Manual-acknowledgment listener infrastructure, enabled only when the
 * delivery worker is enabled. Infrastructure failures retry indefinitely with
 * a short bounded transport backoff; poison commands are handled in the
 * listener and acknowledged explicitly.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@ConditionalOnProperty(
        prefix = "webhook.delivery.worker",
        name = "enabled",
        havingValue = "true"
)
public class DeliveryKafkaConfiguration {

    private static final long INFRASTRUCTURE_RETRY_DELAY_MILLIS = 1_000L;

    @Bean(name = "deliveryKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> deliveryKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DeliveryWorkerProperties properties
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(properties.getConcurrency());
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                new FixedBackOff(
                        INFRASTRUCTURE_RETRY_DELAY_MILLIS,
                        FixedBackOff.UNLIMITED_ATTEMPTS
                )
        ));
        return factory;
    }
}
