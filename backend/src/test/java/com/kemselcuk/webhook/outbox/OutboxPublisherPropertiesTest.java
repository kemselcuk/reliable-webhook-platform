package com.kemselcuk.webhook.outbox;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPublisherPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsAreBoundedAndPositive() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties();

        assertThat(properties.getBatchSize()).isEqualTo(50);
        assertThat(properties.hasPositiveDurations()).isTrue();
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void validationRejectsOutOfRangeBatchAndNonPositiveDurations() {
        OutboxPublisherProperties properties = new OutboxPublisherProperties();

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
        assertThat(validator.validate(properties).stream()
                .map(violation -> violation.getPropertyPath().toString()))
                .contains("positiveDurations");
    }
}
