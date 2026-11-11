package com.edp.iot.common.notify;

import com.edp.iot.common.change.ChangeKind;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.notice.CustomerNotice;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTypeTest {

    private static LineChange change(ChangeKind kind, boolean notify, Map<String, Object> details) {
        return LineChange.of("8901400000000000001", "customer-001", kind, notify, null, Instant.now(), details);
    }

    @Test
    void onlyChangesMarkedForTheCustomerBecomeNotifications() {
        assertThat(NotificationType.of(change(ChangeKind.OFFLINE, false, Map.of()))).isEmpty();
        assertThat(NotificationType.of(change(ChangeKind.OFFLINE, true, Map.of()))).contains(NotificationType.DEVICE_OFFLINE);
        assertThat(NotificationType.of(change(ChangeKind.ATTACHED, true, Map.of()))).isEmpty();
    }

    @Test
    void usageThresholdsAreSeparateTypes() {
        assertThat(NotificationType.of(change(ChangeKind.USAGE_THRESHOLD, true, Map.of("thresholdPct", 80))))
                .contains(NotificationType.USAGE_80);
        assertThat(NotificationType.of(change(ChangeKind.USAGE_THRESHOLD, true, Map.of("thresholdPct", 100))))
                .contains(NotificationType.USAGE_100);
    }

    @Test
    void requestOutcomesAndOutageNoticesMap() {
        assertThat(NotificationType.of(change(ChangeKind.REQUEST_FAILED, true, Map.of()))).contains(NotificationType.REQUEST_FAILED);
        CustomerNotice notice = new CustomerNotice(UUID.randomUUID(), "customer-001", CustomerNotice.Kind.OUTAGE_ENDED,
                Instant.now(), Map.of());
        assertThat(NotificationType.of(notice)).isEqualTo(NotificationType.OUTAGE_ENDED);
        assertThat(NotificationType.parse("USAGE_80")).contains(NotificationType.USAGE_80);
        assertThat(NotificationType.parse("usage_80")).isEmpty();
    }
}
