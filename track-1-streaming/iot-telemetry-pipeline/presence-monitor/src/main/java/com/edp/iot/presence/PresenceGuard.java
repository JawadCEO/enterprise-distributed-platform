package com.edp.iot.presence;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether offline marking may run. After any unhealthy moment, including startup, the
 * platform must stay healthy for one full keep-alive window plus grace before a line can be called
 * silent: only then has every device had a fair chance to report. The longest window in the fleet
 * is used, so no kind of device is judged early.
 */
class PresenceGuard {

    private Instant healthySince;

    /**
     * @param healthy          whether ingestion and processing are healthy right now
     * @param requiredHealthy  longest keep-alive window in the fleet plus grace
     * @return true when offline marking may run
     */
    synchronized boolean allows(boolean healthy, Instant now, Duration requiredHealthy) {
        if (!healthy) {
            healthySince = null;
            return false;
        }
        if (healthySince == null) {
            healthySince = now;
        }
        return !now.isBefore(healthySince.plus(requiredHealthy));
    }

    synchronized Instant healthySince() {
        return healthySince;
    }
}
