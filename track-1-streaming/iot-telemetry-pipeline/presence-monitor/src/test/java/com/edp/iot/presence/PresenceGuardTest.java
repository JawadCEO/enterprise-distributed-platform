package com.edp.iot.presence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceGuardTest {

    private static final Instant T0 = Instant.parse("2026-11-04T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(90);

    @Test
    void waitsOneFullWindowAfterStartup() {
        PresenceGuard guard = new PresenceGuard();

        assertThat(guard.allows(true, T0, WINDOW)).isFalse();
        assertThat(guard.allows(true, T0.plusSeconds(89), WINDOW)).isFalse();
        assertThat(guard.allows(true, T0.plusSeconds(90), WINDOW)).isTrue();
    }

    @Test
    void outageRestartsTheWait() {
        PresenceGuard guard = new PresenceGuard();
        guard.allows(true, T0, WINDOW);
        assertThat(guard.allows(true, T0.plusSeconds(100), WINDOW)).isTrue();

        assertThat(guard.allows(false, T0.plusSeconds(110), WINDOW)).isFalse();
        assertThat(guard.allows(true, T0.plusSeconds(400), WINDOW)).isFalse();
        assertThat(guard.allows(true, T0.plusSeconds(489), WINDOW)).isFalse();
        assertThat(guard.allows(true, T0.plusSeconds(490), WINDOW)).isTrue();
    }
}
