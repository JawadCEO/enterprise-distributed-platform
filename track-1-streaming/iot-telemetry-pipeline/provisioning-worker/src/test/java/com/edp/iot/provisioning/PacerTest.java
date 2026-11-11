package com.edp.iot.provisioning;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PacerTest {

    @Test
    void allowsOneSecondOfBurstThenAsksToWait() {
        Pacer pacer = new Pacer(new ProvisioningProperties("http://x", 5, Duration.ofMillis(250), Duration.ofSeconds(10),
                Duration.ofSeconds(5), Duration.ofDays(90), Duration.ofSeconds(2), 1));
        for (int i = 0; i < 5; i++) {
            assertThat(pacer.tryAcquire()).isZero();
        }
        long wait = pacer.tryAcquire();
        assertThat(wait).isBetween(1L, 200L);
    }
}
