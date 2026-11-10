package com.edp.iot.state;

import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.state.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Background jobs. Both are safe to run on every instance at once. */
@Component
class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final StateStore store;
    private final LineChangeOutbox outbox;
    private final StateProperties props;

    MaintenanceJobs(StateStore store, LineChangeOutbox outbox, StateProperties props) {
        this.store = store;
        this.outbox = outbox;
        this.props = props;
    }

    /** Keeps daily history partitions ahead of time and drops expired ones (an advisory lock picks one runner). */
    @Scheduled(initialDelay = 0, fixedDelay = 3_600_000)
    void maintainPartitions() {
        try {
            store.maintainPartitions(props.historyRetention().toDays());
        } catch (RuntimeException e) {
            log.warn("Partition maintenance failed: {}", e.toString());
        }
    }

    /** Re-sends line changes that were committed but not published (SKIP LOCKED splits the work). */
    @Scheduled(initialDelay = 10_000, fixedDelay = 5_000)
    void relayOutbox() {
        try {
            int sent = outbox.relay(Duration.ofSeconds(10), 500);
            if (sent > 0) {
                log.info("Outbox relay re-sent {} line changes", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Outbox relay failed: {}", e.toString());
        }
    }
}
