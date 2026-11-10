package com.edp.iot.ingest.events;

import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.line.LineInfo;
import com.edp.iot.common.line.LineRegistry;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.line.SimType;
import com.edp.iot.ingest.ratelimit.RateLimiter;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.edp.iot.ingest.events.EventValidatorTest.PROPS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestServiceTest {

    static final String ACTIVE = "8901260000000000001";
    static final String DEACTIVATED = "8901260000000000002";
    static final String UNKNOWN = "8901260000000000009";

    private final EventPublisher publisher = mock(EventPublisher.class);
    private final LineRegistry registry = mock(LineRegistry.class);

    IngestServiceTest() {
        when(registry.findAll(anyCollection())).thenReturn(Map.of(
                ACTIVE, line(ACTIVE, LineState.ACTIVE),
                DEACTIVATED, line(DEACTIVATED, LineState.DEACTIVATED)));
    }

    @Test
    void acceptsOnlyWhatKafkaConfirmedAndAddsTheCustomerFromTheRegistry() {
        when(publisher.publish(anyList())).thenReturn(new boolean[]{true, false});
        var response = service(limiter(10)).ingest(List.of(event(ACTIVE, 1), event(ACTIVE, 2)));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.results()).extracting(EventResult::status).containsExactly("ACCEPTED", "RETRY");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NetworkEvent>> sent = ArgumentCaptor.forClass(List.class);
        verify(publisher).publish(sent.capture());
        assertThat(sent.getValue()).extracting(NetworkEvent::customerId).containsOnly("cust-1");
    }

    @Test
    void unknownAndDeactivatedLinesAreRejectedPerEventAndNeverPublished() {
        var response = service(limiter(10)).ingest(List.of(event(UNKNOWN, 1), event(DEACTIVATED, 1)));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.results()).extracting(EventResult::code).containsExactly("UNKNOWN_LINE", "INACTIVE_LINE");
        verify(publisher, never()).publish(anyList());
    }

    @Test
    void returns503WhenKafkaConfirmedNothing() {
        when(publisher.publish(anyList())).thenReturn(new boolean[]{false});
        var response = service(limiter(10)).ingest(List.of(event(ACTIVE, 1)));

        assertThat(response.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.retryAfterMs()).isNotNull();
    }

    @Test
    void rateLimitsTheEventsBeyondTheAllowance() {
        var response = service(limiter(0)).ingest(List.of(event(ACTIVE, 1), event(ACTIVE, 2)));

        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.results()).extracting(EventResult::status).containsOnly("RATE_LIMITED");
        verify(publisher, never()).publish(anyList());
    }

    @Test
    void invalidEventsAreRejectedWithoutBlockingValidOnes() {
        when(publisher.publish(anyList())).thenReturn(new boolean[]{true});
        JsonNode bad = EventValidatorTest.node("{\"lineId\":\"" + ACTIVE + "\"}");
        var response = service(limiter(10)).ingest(List.of(bad, event(ACTIVE, 5)));

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.results()).extracting(EventResult::status).containsExactly("REJECTED", "ACCEPTED");
    }

    private IngestService service(RateLimiter limiter) {
        return new IngestService(new EventValidator(PROPS), registry, limiter, publisher, PROPS, new SimpleMeterRegistry());
    }

    private static RateLimiter limiter(int allowance) {
        return (LineInfo line, int requested) -> Math.min(allowance, requested);
    }

    private static LineInfo line(String iccid, LineState state) {
        return new LineInfo(iccid, "cust-1", SimType.REMOVABLE, "TRACKER", state, 300, 0, "local", 1000, false);
    }

    private static JsonNode event(String iccid, long seq) {
        return EventValidatorTest.node("""
                {"eventId":"%s","type":"KEEP_ALIVE","lineId":"%s","imei":"356938035643809","bootId":1,"sequence":%d,
                 "deviceTime":"%s"}""".formatted(UUID.randomUUID(), iccid, seq, Instant.now()));
    }
}
