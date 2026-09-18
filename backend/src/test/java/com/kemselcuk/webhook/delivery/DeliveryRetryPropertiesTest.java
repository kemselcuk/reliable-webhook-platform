package com.kemselcuk.webhook.delivery;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryRetryPropertiesTest {

    @Test
    void defaultsMatchTheLocalRetryPolicyContract() {
        DeliveryRetryProperties properties = new DeliveryRetryProperties();

        assertThat(properties.getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getInitialDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getMaxDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.getJitterFactor()).isEqualTo(0.20);
        assertThat(properties.hasValidDelays()).isTrue();
    }

    @Test
    void rejectsNonPositiveOrReversedDurations() {
        DeliveryRetryProperties properties = new DeliveryRetryProperties();

        properties.setInitialDelay(Duration.ZERO);
        assertThat(properties.hasValidDelays()).isFalse();

        properties.setInitialDelay(Duration.ofSeconds(2));
        properties.setMaxDelay(Duration.ofSeconds(1));
        assertThat(properties.hasValidDelays()).isFalse();

        properties.setInitialDelay(Duration.ofSeconds(-1));
        properties.setMaxDelay(Duration.ofSeconds(1));
        assertThat(properties.hasValidDelays()).isFalse();
    }

    @Test
    void validatesAttemptAndJitterBoundsWithBeanValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        DeliveryRetryProperties properties = new DeliveryRetryProperties();

        properties.setMaxAttempts(0);
        assertThat(paths(validator.validate(properties))).contains("maxAttempts");

        properties.setMaxAttempts(101);
        assertThat(paths(validator.validate(properties))).contains("maxAttempts");

        properties.setMaxAttempts(5);
        properties.setJitterFactor(-0.01);
        assertThat(paths(validator.validate(properties))).contains("jitterFactor");

        properties.setJitterFactor(1.01);
        assertThat(paths(validator.validate(properties))).contains("jitterFactor");
    }

    @Test
    void rejectsNonFiniteJitterThroughTheCrossFieldInvariant() {
        DeliveryRetryProperties properties = new DeliveryRetryProperties();
        properties.setJitterFactor(Double.NaN);

        assertThat(properties.hasValidDelays()).isFalse();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        assertThat(paths(validator.validate(properties))).contains("validDelays");
    }

    private static Set<String> paths(Set<ConstraintViolation<DeliveryRetryProperties>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
