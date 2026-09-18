package com.kemselcuk.webhook.delivery;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRetrySchedulerPropertiesTest {

    @Test
    void defaultsAreDisabledAndBounded() {
        DeliveryRetrySchedulerProperties properties = new DeliveryRetrySchedulerProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.hasPositivePollInterval()).isTrue();
    }

    @Test
    void beanValidationRejectsInvalidBatchAndPollInterval() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        DeliveryRetrySchedulerProperties properties = new DeliveryRetrySchedulerProperties();

        properties.setBatchSize(0);
        assertThat(validator.validate(properties).stream()
                .map(violation -> violation.getPropertyPath().toString()))
                .contains("batchSize");

        properties.setBatchSize(501);
        assertThat(validator.validate(properties).stream()
                .map(violation -> violation.getPropertyPath().toString()))
                .contains("batchSize");

        properties.setBatchSize(50);
        properties.setPollInterval(Duration.ZERO);
        assertThat(properties.hasPositivePollInterval()).isFalse();
        assertThat(validator.validate(properties).stream()
                .map(violation -> violation.getPropertyPath().toString()))
                .contains("positivePollInterval");
    }
}
