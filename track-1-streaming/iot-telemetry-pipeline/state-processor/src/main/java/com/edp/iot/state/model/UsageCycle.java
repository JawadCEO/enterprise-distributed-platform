package com.edp.iot.state.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * One line's usage in one billing cycle ({@code usage_cycle}). A cycle is a calendar month in UTC
 * [assumption]. The two flags make each threshold notification happen once per cycle (FR-25),
 * even when usage records are replayed.
 */
public record UsageCycle(String iccid, LocalDate cycleStart, long bytesUsed, long allowanceBytes,
                         boolean notified80, boolean notified100) {

    public static LocalDate cycleOf(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
    }

    public static UsageCycle empty(String iccid, LocalDate cycleStart, long allowanceBytes) {
        return new UsageCycle(iccid, cycleStart, 0, allowanceBytes, false, false);
    }

    public UsageCycle plus(long bytes) {
        return new UsageCycle(iccid, cycleStart, bytesUsed + bytes, allowanceBytes, notified80, notified100);
    }

    public UsageCycle withNotified(boolean at80, boolean at100) {
        return new UsageCycle(iccid, cycleStart, bytesUsed, allowanceBytes, at80, at100);
    }

    /** Share of the allowance used, in percent; 0 when the plan has no allowance. */
    public double percentUsed() {
        return allowanceBytes <= 0 ? 0 : bytesUsed * 100.0 / allowanceBytes;
    }
}
