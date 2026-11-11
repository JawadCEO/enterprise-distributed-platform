package com.edp.iot.api.requests;

import io.micrometer.core.instrument.Gauge;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request outcomes (NFR-21), read from {@code line_request} rather than counted where each request
 * finishes: a request can end in state-processor (the network's answer) or in the sweeper (time
 * limit), and a count inside a transaction that rolls back would be wrong. Every 15 s: requests
 * finished in the last 5 minutes by status and reason, how many are pending, and the p95 from
 * {@code 202} to the end (NFR-2b).
 */
@Component
class RequestMetrics {

    private static final Logger log = LoggerFactory.getLogger(RequestMetrics.class);

    private final JdbcTemplate jdbc;
    private final MultiGauge finished;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong p95Millis = new AtomicLong();

    RequestMetrics(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.finished = MultiGauge.builder("iot.requests.finished.5m")
                .description("Requests finished in the last 5 minutes, by status and reason").register(meters);
        Gauge.builder("iot.requests.pending", pending, AtomicLong::get).register(meters);
        Gauge.builder("iot.requests.completion.p95.5m", p95Millis, v -> v.get() / 1000.0)
                .description("p95 seconds from 202 to COMPLETED or FAILED, last 5 minutes (NFR-2b)")
                .baseUnit("seconds").register(meters);
    }

    @Scheduled(fixedDelayString = "${iot.requests.metrics-interval-ms:15000}")
    void refresh() {
        try {
            List<MultiGauge.Row<?>> rows = new ArrayList<>();
            jdbc.query("""
                    SELECT status, COALESCE(reason, 'NONE'), count(*) FROM line_request
                    WHERE completed_at > now() - interval '5 minutes' GROUP BY 1, 2
                    """, rs -> {
                rows.add(MultiGauge.Row.of(Tags.of("status", rs.getString(1), "reason", rs.getString(2)), rs.getLong(3)));
            });
            finished.register(rows, true);
            Long n = jdbc.queryForObject("SELECT count(*) FROM line_request WHERE status = 'PENDING'", Long.class);
            pending.set(n == null ? 0 : n);
            Double p95 = jdbc.queryForObject("""
                    SELECT percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM completed_at - created_at))
                    FROM line_request WHERE completed_at > now() - interval '5 minutes'
                    """, Double.class);
            p95Millis.set(p95 == null ? 0 : Math.round(p95 * 1000));
        } catch (RuntimeException e) {
            log.debug("Request metrics not refreshed: {}", e.toString());
        }
    }
}
