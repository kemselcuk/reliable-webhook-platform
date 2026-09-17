package com.kemselcuk.webhook.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kemselcuk.webhook.domain.DeliveryAttemptOutcome;
import com.kemselcuk.webhook.domain.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-09-18T10:00:00Z");
    private static final UUID DELIVERY_ID = UUID.randomUUID();
    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();
    private static final UUID CLAIM_TOKEN = UUID.randomUUID();

    @Test
    void unclaimableCommandDoesNotMakeHttpCall() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.unclaimable(
                        DeliveryClaimDisposition.BUSY, DeliveryStatus.PROCESSING
                ));
        DeliveryWorker worker = worker(claimStore, httpClient);

        DeliveryWorkerResult result = worker.process(DELIVERY_ID);

        assertThat(result.disposition()).isEqualTo(DeliveryWorkerDisposition.BUSY);
        verify(httpClient, never()).post(any());
        verify(claimStore, never()).completeSuccess(any(), any(), any(), any());
        verify(claimStore, never()).completeFailure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void twoHundredResponseCompletesSuccessWithStatus() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        DeliveryWorkSnapshot work = work();
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.claimed(work));
        when(httpClient.post(work)).thenReturn(DeliveryHttpResult.httpStatus(204));
        when(claimStore.completeSuccess(eq(work), eq(204), eq(NOW), eq(NOW)))
                .thenReturn(true);
        DeliveryWorker worker = worker(claimStore, httpClient);

        DeliveryWorkerResult result = worker.process(DELIVERY_ID);

        assertThat(result.disposition()).isEqualTo(DeliveryWorkerDisposition.SUCCESS);
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.SUCCESS);
        assertThat(result.httpStatus()).isEqualTo(204);
        verify(claimStore).completeSuccess(work, 204, NOW, NOW);
        verify(claimStore, never()).completeFailure(any(), any(), any(), any(), any(), any());
    }

    @Test
    void nonTwoHundredResponseCompletesFailedWithBasicRetryableClassification() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        DeliveryWorkSnapshot work = work();
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.claimed(work));
        when(httpClient.post(work)).thenReturn(DeliveryHttpResult.httpStatus(503));
        when(claimStore.completeFailure(
                eq(work), eq(DeliveryAttemptOutcome.RETRYABLE_FAILURE), eq(503),
                eq("HTTP_503"), eq(NOW), eq(NOW)
        )).thenReturn(true);
        DeliveryWorker worker = worker(claimStore, httpClient);

        DeliveryWorkerResult result = worker.process(DELIVERY_ID);

        assertThat(result.disposition()).isEqualTo(DeliveryWorkerDisposition.FAILED);
        assertThat(result.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(result.httpStatus()).isEqualTo(503);
        verify(claimStore).completeFailure(
                work, DeliveryAttemptOutcome.RETRYABLE_FAILURE, 503,
                "HTTP_503", NOW, NOW
        );
    }

    @Test
    void transportFailureCompletesRetryableFailureWithoutHttpStatus() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        DeliveryWorkSnapshot work = work();
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.claimed(work));
        when(httpClient.post(work)).thenReturn(DeliveryHttpResult.transportFailure(
                DeliveryTransportFailure.TIMEOUT
        ));
        when(claimStore.completeFailure(
                eq(work), eq(DeliveryAttemptOutcome.RETRYABLE_FAILURE), isNull(Integer.class),
                eq("TIMEOUT"), eq(NOW), eq(NOW)
        )).thenReturn(true);
        DeliveryWorker worker = worker(claimStore, httpClient);

        DeliveryWorkerResult result = worker.process(DELIVERY_ID);

        assertThat(result.disposition()).isEqualTo(DeliveryWorkerDisposition.FAILED);
        assertThat(result.httpStatus()).isNull();
        assertThat(result.transportFailure()).isEqualTo(DeliveryTransportFailure.TIMEOUT);
        verify(claimStore).completeFailure(
                work, DeliveryAttemptOutcome.RETRYABLE_FAILURE, null,
                "TIMEOUT", NOW, NOW
        );
    }

    @Test
    void staleCompletionIsExposedToCaller() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        DeliveryWorkSnapshot work = work();
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.claimed(work));
        when(httpClient.post(work)).thenReturn(DeliveryHttpResult.httpStatus(200));
        when(claimStore.completeSuccess(work, 200, NOW, NOW)).thenReturn(false);
        DeliveryWorker worker = worker(claimStore, httpClient);

        DeliveryWorkerResult result = worker.process(DELIVERY_ID);

        assertThat(result.disposition()).isEqualTo(DeliveryWorkerDisposition.STALE_COMPLETION);
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.deliveryStatus()).isNull();
    }

    @Test
    void permanentFourHundredResponseUsesPermanentFailureOutcome() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        DeliveryWorkSnapshot work = work();
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.claimed(work));
        when(httpClient.post(work)).thenReturn(DeliveryHttpResult.httpStatus(404));
        when(claimStore.completeFailure(
                eq(work), eq(DeliveryAttemptOutcome.PERMANENT_FAILURE), eq(404),
                eq("HTTP_404"), eq(NOW), eq(NOW)
        )).thenReturn(true);
        DeliveryWorker worker = worker(claimStore, httpClient);

        assertThat(worker.process(DELIVERY_ID).disposition())
                .isEqualTo(DeliveryWorkerDisposition.FAILED);
        verify(claimStore).completeFailure(
                work, DeliveryAttemptOutcome.PERMANENT_FAILURE, 404,
                "HTTP_404", NOW, NOW
        );
    }

    @Test
    void redirectResponseUsesPermanentFailureOutcomeWhenRedirectsAreDisabled() {
        DeliveryClaimStore claimStore = mock(DeliveryClaimStore.class);
        DeliveryHttpClient httpClient = mock(DeliveryHttpClient.class);
        DeliveryWorkSnapshot work = work();
        when(claimStore.claim(DELIVERY_ID, NOW, Duration.ofMinutes(5)))
                .thenReturn(DeliveryClaimResult.claimed(work));
        when(httpClient.post(work)).thenReturn(DeliveryHttpResult.httpStatus(302));
        when(claimStore.completeFailure(
                eq(work), eq(DeliveryAttemptOutcome.PERMANENT_FAILURE), eq(302),
                eq("HTTP_302"), eq(NOW), eq(NOW)
        )).thenReturn(true);
        DeliveryWorker worker = worker(claimStore, httpClient);

        assertThat(worker.process(DELIVERY_ID).disposition())
                .isEqualTo(DeliveryWorkerDisposition.FAILED);
        verify(claimStore).completeFailure(
                work, DeliveryAttemptOutcome.PERMANENT_FAILURE, 302,
                "HTTP_302", NOW, NOW
        );
    }

    private DeliveryWorker worker(DeliveryClaimStore claimStore, DeliveryHttpClient httpClient) {
        DeliveryWorkerProperties properties = new DeliveryWorkerProperties();
        properties.setClaimTimeout(Duration.ofMinutes(5));
        return new DeliveryWorker(
                claimStore,
                httpClient,
                properties,
                new BasicDeliveryOutcomeClassifier(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private DeliveryWorkSnapshot work() {
        try {
            return new DeliveryWorkSnapshot(
                    DELIVERY_ID,
                    EVENT_ID,
                    ENDPOINT_ID,
                    "order.created",
                    new ObjectMapper().readTree("{\"orderId\":\"order-123\"}"),
                    "http://localhost/hooks",
                    true,
                    CLAIM_TOKEN,
                    1
            );
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
