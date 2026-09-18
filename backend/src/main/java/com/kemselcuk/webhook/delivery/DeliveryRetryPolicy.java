package com.kemselcuk.webhook.delivery;

import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/** Pure retry classification and delay calculation; it never sleeps or schedules. */
@Component
public class DeliveryRetryPolicy {

    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    private final DeliveryRetryProperties properties;
    private final RetryJitterSource jitterSource;

    public DeliveryRetryPolicy(
            DeliveryRetryProperties properties,
            RetryJitterSource jitterSource
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
    }

    public DeliveryRetryDecision decide(
            DeliveryHttpResult result,
            int currentRunAttemptNumber,
            Instant completedAt
    ) {
        if (result == null || completedAt == null) {
            throw new IllegalArgumentException("result and completedAt are required");
        }
        validateAttempt(currentRunAttemptNumber);
        validateConfiguration();

        if (result.hasHttpStatus() && isSuccess(result.httpStatus())) {
            throw new IllegalArgumentException("successful HTTP result is not a retry decision");
        }

        if (!isRetryable(result)) {
            return DeliveryRetryDecision.permanent(errorCode(result));
        }
        if (currentRunAttemptNumber >= properties.getMaxAttempts()) {
            return DeliveryRetryDecision.dead(errorCode(result));
        }

        Duration exponential = exponentialDelay(currentRunAttemptNumber);
        Duration jittered = applyJitter(exponential);
        Duration retryAfter = parseRetryAfter(result.retryAfter(), completedAt);
        Duration delay = cap(max(jittered, retryAfter), properties.getMaxDelay());
        return DeliveryRetryDecision.scheduled(
                safePlus(completedAt, delay),
                errorCode(result)
        );
    }

    private void validateAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("currentRunAttemptNumber must be positive");
        }
    }

    private void validateConfiguration() {
        if (!properties.hasValidDelays()
                || properties.getMaxAttempts() < 1
                || properties.getMaxAttempts() > 100
                || properties.getJitterFactor() < 0.0
                || properties.getJitterFactor() > 1.0
                || !Double.isFinite(properties.getJitterFactor())) {
            throw new IllegalStateException("invalid retry policy configuration");
        }
    }

    private static boolean isRetryable(DeliveryHttpResult result) {
        return !result.hasHttpStatus()
                || result.httpStatus() == 429
                || result.httpStatus() >= 500;
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status <= 299;
    }

    private static String errorCode(DeliveryHttpResult result) {
        return result.hasHttpStatus()
                ? "HTTP_" + result.httpStatus()
                : result.transportFailure().name();
    }

    private Duration exponentialDelay(int attemptNumber) {
        Duration delay = properties.getInitialDelay();
        Duration maximum = properties.getMaxDelay();
        for (int exponent = 1; exponent < attemptNumber; exponent++) {
            if (delay.compareTo(maximum) >= 0) {
                return maximum;
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return maximum;
            }
        }
        return cap(delay, maximum);
    }

    private Duration applyJitter(Duration base) {
        double sample = jitterSource.nextUnitDouble();
        if (!Double.isFinite(sample) || sample < 0.0 || sample > 1.0) {
            throw new IllegalStateException("retry jitter source must return a value in [0, 1]");
        }
        double factor = properties.getJitterFactor();
        double multiplier = 1.0 - factor + (2.0 * factor * sample);
        BigInteger baseNanos = toNanos(base);
        BigInteger jitteredNanos = new BigDecimal(baseNanos)
                .multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.HALF_UP)
                .toBigIntegerExact();
        BigInteger bounded = jitteredNanos.max(BigInteger.ZERO).min(toNanos(properties.getMaxDelay()));
        return fromNanos(bounded);
    }

    private static Duration parseRetryAfter(String value, Instant completedAt) {
        if (value == null || value.isBlank() || value.length() > 128) {
            return Duration.ZERO;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 128) {
            return Duration.ZERO;
        }
        if (isAsciiDigits(trimmed)) {
            try {
                return Duration.ofSeconds(Long.parseLong(trimmed));
            } catch (NumberFormatException exception) {
                return Duration.ZERO;
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(
                    trimmed,
                    DateTimeFormatter.RFC_1123_DATE_TIME
            ).toInstant();
            Duration delay = Duration.between(completedAt, retryAt);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeException | ArithmeticException exception) {
            return Duration.ZERO;
        }
    }

    private static boolean isAsciiDigits(String value) {
        return value.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private static Instant safePlus(Instant instant, Duration delay) {
        try {
            return instant.plus(delay);
        } catch (ArithmeticException exception) {
            return Instant.MAX;
        }
    }

    private static BigInteger toNanos(Duration duration) {
        return BigInteger.valueOf(duration.getSeconds())
                .multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(duration.getNano()));
    }

    private static Duration fromNanos(BigInteger nanos) {
        BigInteger[] parts = nanos.divideAndRemainder(NANOS_PER_SECOND);
        return Duration.ofSeconds(parts[0].longValueExact(), parts[1].intValueExact());
    }

    private static Duration max(Duration first, Duration second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static Duration cap(Duration value, Duration maximum) {
        return value.compareTo(maximum) <= 0 ? value : maximum;
    }
}
