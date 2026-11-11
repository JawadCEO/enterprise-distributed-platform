package com.edp.iot.provisioning;

import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Fails every request still {@code PENDING} past its deadline with {@code NETWORK_TIMEOUT} and puts
 * the line back where it was (FR-7, FR-8). Safe on every instance at once: each request is locked
 * and re-checked before it is failed.
 */
@Component
class Sweeper {

    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);

    private final RequestStore store;
    private final Finisher finisher;
    private final JdbcTemplate jdbc;
    private final ProvisioningProperties props;

    Sweeper(RequestStore store, Finisher finisher, JdbcTemplate jdbc, ProvisioningProperties props) {
        this.store = store;
        this.finisher = finisher;
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(initialDelay = 5_000, fixedDelayString = "${iot.provisioning.sweep-interval-ms:5000}")
    void sweep() {
        try {
            List<LineRequest> overdue;
            do {
                overdue = store.overdue(Instant.now(), 200);
                int failed = finisher.fail(overdue, RequestStore.REASON_NETWORK_TIMEOUT);
                if (failed > 0) {
                    log.info("Sweeper failed {} overdue requests with {}", failed, RequestStore.REASON_NETWORK_TIMEOUT);
                }
            } while (overdue.size() == 200);
        } catch (RuntimeException e) {
            log.warn("Sweep failed: {}", e.toString());
        }
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    void expire() {
        try {
            int deleted = jdbc.update("DELETE FROM line_request WHERE status <> 'PENDING' AND created_at < ?",
                    Timestamp.from(Instant.now().minus(props.retention())));
            if (deleted > 0) {
                log.info("Deleted {} requests older than {}", deleted, props.retention());
            }
        } catch (RuntimeException e) {
            log.warn("Request expiry failed: {}", e.toString());
        }
    }
}
