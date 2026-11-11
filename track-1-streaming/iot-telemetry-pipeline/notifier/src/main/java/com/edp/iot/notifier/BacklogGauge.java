package com.edp.iot.notifier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Webhook backlog per customer (NFR-21): notifications still pending, and the age of the oldest
 * one, so one customer's dead endpoint shows up as that customer's backlog and nobody else's.
 */
@Component
class BacklogGauge {

    private static final Logger log = LoggerFactory.getLogger(BacklogGauge.class);

    private final JdbcTemplate jdbc;
    private final MultiGauge pending;
    private final MultiGauge oldestSeconds;

    BacklogGauge(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.pending = MultiGauge.builder("iot.notifier.backlog")
                .description("Notifications pending delivery, per customer").register(meters);
        this.oldestSeconds = MultiGauge.builder("iot.notifier.backlog.oldest.seconds")
                .description("Age of the oldest pending notification, per customer").register(meters);
    }

    @Scheduled(fixedDelayString = "${iot.notifier.backlog-interval-ms:15000}")
    void refresh() {
        try {
            List<MultiGauge.Row<?>> counts = new ArrayList<>();
            List<MultiGauge.Row<?>> ages = new ArrayList<>();
            jdbc.query("""
                    SELECT customer_id, count(*), extract(epoch FROM now() - min(created_at))
                    FROM notification WHERE status = 'PENDING' GROUP BY customer_id
                    """, rs -> {
                Tags tags = Tags.of("customer", rs.getString(1));
                counts.add(MultiGauge.Row.of(tags, rs.getLong(2)));
                ages.add(MultiGauge.Row.of(tags, rs.getDouble(3)));
            });
            pending.register(counts, true);
            oldestSeconds.register(ages, true);
        } catch (RuntimeException e) {
            log.debug("Backlog gauge not refreshed: {}", e.toString());
        }
    }
}
