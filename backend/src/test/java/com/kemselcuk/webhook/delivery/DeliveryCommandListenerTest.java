package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryCommandListenerTest {

    private static final UUID DELIVERY_ID = UUID.fromString(
            "f1f4b2cd-6f96-4b6a-b7df-1dbd3be4e9bc"
    );

    @Test
    void malformedCommandIsAckedAndWorkerIsNotCalled() {
        DeliveryWorker worker = mock(DeliveryWorker.class);
        DeliveryCommandListener listener = listener(worker);
        RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

        listener.onMessage(record("bad", "not-json"), acknowledgment);

        assertThat(acknowledgment.acknowledged).isTrue();
        assertThat(acknowledgment.nackDelay).isNull();
        verify(worker, never()).process(any());
    }

    @Test
    void busyResultNacksWithoutAcknowledging() {
        DeliveryWorker worker = mock(DeliveryWorker.class);
        when(worker.process(DELIVERY_ID)).thenReturn(new DeliveryWorkerResult(
                DeliveryWorkerDisposition.BUSY,
                DeliveryStatus.PROCESSING,
                null,
                null
        ));
        DeliveryCommandListener listener = listener(worker);
        RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

        listener.onMessage(record(
                DELIVERY_ID.toString(),
                "{\"version\":1,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID)
        ), acknowledgment);

        assertThat(acknowledgment.acknowledged).isFalse();
        assertThat(acknowledgment.nackDelay).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void everyNonBusyBoundedResultIsAcked() {
        for (DeliveryWorkerDisposition disposition : new DeliveryWorkerDisposition[]{
                DeliveryWorkerDisposition.SUCCESS,
                DeliveryWorkerDisposition.FAILED,
                DeliveryWorkerDisposition.NOT_FOUND,
                DeliveryWorkerDisposition.TERMINAL,
                DeliveryWorkerDisposition.DISABLED,
                DeliveryWorkerDisposition.INELIGIBLE,
                DeliveryWorkerDisposition.STALE_COMPLETION
        }) {
            DeliveryWorker worker = mock(DeliveryWorker.class);
            when(worker.process(DELIVERY_ID)).thenReturn(resultFor(disposition));
            DeliveryCommandListener listener = listener(worker);
            RecordingAcknowledgment acknowledgment = new RecordingAcknowledgment();

            listener.onMessage(record(
                    DELIVERY_ID.toString(),
                    "{\"version\":1,\"deliveryId\":\"%s\"}".formatted(DELIVERY_ID)
            ), acknowledgment);

            assertThat(acknowledgment.acknowledged)
                    .as("disposition %s", disposition)
                    .isTrue();
            assertThat(acknowledgment.nackDelay).isNull();
        }
    }

    private DeliveryCommandListener listener(DeliveryWorker worker) {
        DeliveryWorkerProperties properties = new DeliveryWorkerProperties();
        return new DeliveryCommandListener(
                new DeliveryCommandParser(new ObjectMapper()), worker, properties
        );
    }

    private static DeliveryWorkerResult resultFor(DeliveryWorkerDisposition disposition) {
        return switch (disposition) {
            case SUCCESS -> DeliveryWorkerResult.success(204);
            case FAILED -> DeliveryWorkerResult.failed(DeliveryStatus.FAILED, 503, null);
            case NOT_FOUND, TERMINAL, DISABLED, INELIGIBLE ->
                    new DeliveryWorkerResult(disposition, null, null, null);
            case STALE_COMPLETION -> DeliveryWorkerResult.staleCompletion(503, null);
            case BUSY -> throw new IllegalArgumentException("not used in this test");
        };
    }

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>("webhook.delivery.commands.v1", 1, 7L, key, value);
    }

    private static final class RecordingAcknowledgment implements Acknowledgment {

        private boolean acknowledged;
        private Duration nackDelay;

        @Override
        public void acknowledge() {
            acknowledged = true;
        }

        @Override
        public void nack(Duration sleep) {
            nackDelay = sleep;
        }
    }
}
