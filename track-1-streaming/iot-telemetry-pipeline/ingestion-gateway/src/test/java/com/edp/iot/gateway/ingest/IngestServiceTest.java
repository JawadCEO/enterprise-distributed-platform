package com.edp.iot.gateway.ingest;

import com.edp.iot.common.event.TelemetryEvent;
import com.edp.iot.common.registry.DeviceInfo;
import com.edp.iot.gateway.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static com.edp.iot.gateway.ingest.EventValidatorTest.DEVICE;
import static com.edp.iot.gateway.ingest.EventValidatorTest.PROPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestServiceTest {

    private final EventPublisher publisher = mock(EventPublisher.class);

    @Test
    void acceptsOnlyWhatKafkaConfirmed() {
        when(publisher.publish(anyList())).thenReturn(new boolean[]{true, false});
        var response = service(limiter(10)).ingest(DEVICE, List.of(event(1), event(2)));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.results()).extracting(EventResult::status).containsExactly("ACCEPTED", "RETRY");
    }

    @Test
    void returns503WhenKafkaConfirmedNothing() {
        when(publisher.publish(anyList())).thenReturn(new boolean[]{false});
        var response = service(limiter(10)).ingest(DEVICE, List.of(event(1)));

        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.retryAfterMs()).isNotNull();
    }

    @Test
    void rateLimitsTheEventsBeyondTheAllowance() {
        when(publisher.publish(anyList())).thenAnswer(inv -> new boolean[((List<TelemetryEvent>) inv.getArgument(0)).size()]);
        var response = service(limiter(0)).ingest(DEVICE, List.of(event(1), event(2)));

        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.results()).extracting(EventResult::status).containsOnly("RATE_LIMITED");
        verify(publisher, never()).publish(anyList());
    }

    @Test
    void invalidEventsAreRejectedWithoutBlockingValidOnes() {
        when(publisher.publish(anyList())).thenReturn(new boolean[]{true});
        JsonNode bad = EventValidatorTest.node("{\"deviceId\":\"dev-1\"}");
        var response = service(limiter(10)).ingest(DEVICE, List.of(bad, event(5)));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.results()).extracting(EventResult::status).containsExactly("REJECTED", "ACCEPTED");
    }

    private IngestService service(RateLimiter limiter) {
        return new IngestService(new EventValidator(PROPS), limiter, publisher, PROPS, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    private static RateLimiter limiter(int allowance) {
        return (DeviceInfo device, int requested) -> Math.min(allowance, requested);
    }

    private static JsonNode event(long seq) {
        return EventValidatorTest.node("""
                {"eventId":"6f1c2a9e-4b7d-4f1a-9c3e-2d8b5a7e1f40","deviceId":"dev-1","bootId":1,"sequence":%d,
                 "deviceTime":"%s","type":"HEARTBEAT"}""".formatted(seq, java.time.Instant.now()));
    }
}
