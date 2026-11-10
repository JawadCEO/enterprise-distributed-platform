package com.edp.iot.processor;

import com.edp.iot.common.outbox.StatusChangeOutbox;
import com.edp.iot.processor.pipeline.TelemetryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Background jobs. Both are safe to run on every instance at once. */
@Component
class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final TelemetryStore store;
    private final StatusChangeOutbox outbox;
    private final ProcessorProperties props;

    MaintenanceJobs(TelemetryStore store, StatusChangeOutbox outbox, ProcessorProperties props) {
        this.store = store;
        this.outbox = outbox;
        this.props = props;
    }

    /** Keeps hourly telemetry partitions ahead of time and drops expired ones (an advisory lock picks one runner). */
    @Scheduled(initialDelay = 0, fixedDelay = 300_000)
    void maintainPartitions() {
        try {
            store.maintainPartitions(props.telemetryRetention().toHours(), props.statusChangeRetention().toDays());
        } catch (RuntimeException e) {
            log.warn("Partition maintenance failed: {}", e.toString());
        }
    }

    /** Re-sends status changes that were committed but not published (SKIP LOCKED splits the work). */
    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    void relayOutbox() {
        try {
            int sent = outbox.relay(Duration.ofSeconds(10), 500);
            if (sent > 0) {
                log.info("Outbox relay re-sent {} status changes", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed: {}", e.toString());
        }
    }
}
