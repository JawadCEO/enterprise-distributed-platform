package com.edp.iot.common.notify;

import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.notice.CustomerNotice;

import java.util.Arrays;
import java.util.Optional;

/**
 * Webhook notification types a customer can subscribe to (FR-22, FR-23). A line change becomes a
 * notification only when it is marked {@code notifyCustomer}; the change kind decides the type.
 */
public enum NotificationType {
    REQUEST_COMPLETED,
    REQUEST_FAILED,
    DEVICE_ONLINE,
    DEVICE_OFFLINE,
    USAGE_80,
    USAGE_100,
    SIM_MOVED,
    LINE_STATE_CHANGED,
    OUTAGE_STARTED,
    OUTAGE_ENDED;

    /** The notification a change should produce, or empty when the customer is not told. */
    public static Optional<NotificationType> of(LineChange change) {
        if (!change.notifyCustomer()) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (change.kind()) {
            case REQUEST_COMPLETED -> REQUEST_COMPLETED;
            case REQUEST_FAILED -> REQUEST_FAILED;
            case ONLINE -> DEVICE_ONLINE;
            case OFFLINE -> DEVICE_OFFLINE;
            case USAGE_THRESHOLD -> change.details().get("thresholdPct") instanceof Number n && n.intValue() >= 100
                    ? USAGE_100 : USAGE_80;
            case SIM_MOVED -> SIM_MOVED;
            case LINE_STATE_CHANGED -> LINE_STATE_CHANGED;
            default -> null;
        });
    }

    public static NotificationType of(CustomerNotice notice) {
        return switch (notice.kind()) {
            case OUTAGE_STARTED -> OUTAGE_STARTED;
            case OUTAGE_ENDED -> OUTAGE_ENDED;
        };
    }

    public static Optional<NotificationType> parse(String value) {
        return Arrays.stream(values()).filter(t -> t.name().equals(value)).findFirst();
    }
}
