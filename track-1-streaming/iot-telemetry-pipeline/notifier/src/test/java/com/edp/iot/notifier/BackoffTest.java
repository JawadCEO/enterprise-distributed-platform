package com.edp.iot.notifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffTest {

    private final Backoff backoff = new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(10));

    @Test
    void doublesFromTheInitialDelayWithJitter() {
        assertThat(backoff.after(1).toMillis()).isBetween(800L, 1200L);
        assertThat(backoff.after(2).toMillis()).isBetween(1600L, 2400L);
        assertThat(backoff.after(5).toMillis()).isBetween(12_800L, 19_200L);
    }

    @Test
    void neverGoesPastTheCapEvenAfterManyFailures() {
        assertThat(backoff.after(40).toMillis()).isBetween(480_000L, 720_000L);
        assertThat(backoff.after(1000).toMillis()).isBetween(480_000L, 720_000L);
    }

    @Test
    void notificationIdIsStablePerChangeAndEndpoint() {
        UUID change = UUID.randomUUID();
        UUID hookA = UUID.randomUUID();
        UUID hookB = UUID.randomUUID();
        assertThat(NotificationWriter.notificationId(change, hookA)).isEqualTo(NotificationWriter.notificationId(change, hookA));
        assertThat(NotificationWriter.notificationId(change, hookA)).isNotEqualTo(NotificationWriter.notificationId(change, hookB));
    }
}
