package com.edp.iot.notifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Parks notifications whose retry period ended without a delivery attempt (their endpoint was
 * paused or removed), and deletes notifications and notices past their 30 days (FR-24).
 */
@Component
class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final JdbcTemplate jdbc;
    private final NotifierProperties props;

    Housekeeping(JdbcTemplate jdbc, NotifierProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${iot.notifier.sweep-interval-ms:10000}")
    void parkExpired() {
        try {
            int parked = jdbc.update("""
                    UPDATE notification
                       SET status = 'PARKED', parked_at = now(), lease_until = NULL,
                           last_error = COALESCE(last_error, 'RETRY_PERIOD_ENDED')
                     WHERE status = 'PENDING' AND retry_since < ? AND (lease_until IS NULL OR lease_until < now())
                    """, Timestamp.from(Instant.now().minus(props.retryFor())));
            if (parked > 0) {
                log.info("Parked {} notifications whose retry period ended", parked);
            }
        } catch (RuntimeException e) {
            log.warn("Parking sweep failed: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${iot.notifier.retention-interval-ms:3600000}", initialDelay = 60_000)
    void expire() {
        try {
            Timestamp cutoff = Timestamp.from(Instant.now().minus(props.retention()));
            int notifications = jdbc.update("DELETE FROM notification WHERE created_at < ? AND status <> 'PENDING'", cutoff);
            int notices = jdbc.update("DELETE FROM customer_notice WHERE at < ? AND published_at IS NOT NULL", cutoff);
            if (notifications + notices > 0) {
                log.info("Expired {} notifications and {} customer notices", notifications, notices);
            }
        } catch (RuntimeException e) {
            log.warn("Retention sweep failed: {}", e.toString());
        }
    }
}
