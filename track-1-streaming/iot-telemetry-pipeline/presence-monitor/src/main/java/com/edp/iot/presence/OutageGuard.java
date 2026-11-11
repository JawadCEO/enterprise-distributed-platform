package com.edp.iot.presence;

import com.edp.iot.common.Json;
import com.edp.iot.common.line.Connection;
import com.edp.iot.common.notice.CustomerNotice;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The outage guard (FR-21). When a region's event rate collapses while the platform itself is
 * healthy, the region is in an outage: each customer with active lines there gets one
 * {@code OUTAGE_STARTED} notice, and lines in the region that go past their deadline are marked
 * offline with reason {@code REGION_OUTAGE} and no alert. When the rate comes back, each of those
 * customers gets one {@code OUTAGE_ENDED} notice with how many of its lines are still offline.
 *
 * <p>The rate is compared with the region's own rate over the minutes before, so a region of
 * meters (one report an hour) and a region of trackers (one a minute) are judged alike. The outage
 * row's partial unique index lets only one instance declare an outage for a region.
 */
@Component
class OutageGuard {

    private static final Logger log = LoggerFactory.getLogger(OutageGuard.class);

    record Outage(UUID outageId, String region, Instant startedAt, Instant endedAt, double baselinePerSec) {
    }

    /**
     * @param covered     regions whose lines must not be alerted now: open outages, and outages that
     *                    ended less than {@code outageSettle} ago
     * @param fleetSilent every region went quiet at once: that points at our side or the whole
     *                    network, not one region, so no outage is declared and no line is judged
     */
    record Decision(Set<String> covered, boolean fleetSilent) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RegionRates rates;
    private final CustomerNoticeOutbox notices;
    private final PresenceProperties props;
    private final AtomicLong openGauge;
    /**
     * While every region is quiet, each region's usual rate when it started. It is kept in
     * PostgreSQL, so neither a restart of this service nor a Redis restart forgets it and starts
     * judging silent lines.
     */
    private static final String FLEET_SILENT = "fleet-silent";

    OutageGuard(JdbcTemplate jdbc, TransactionTemplate tx, RegionRates rates, CustomerNoticeOutbox notices,
                PresenceProperties props, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.rates = rates;
        this.notices = notices;
        this.props = props;
        this.openGauge = meters.gauge("iot.presence.outages.open", new AtomicLong());
    }

    /**
     * Starts and ends outages from the latest rates. Call only while the platform is healthy, or
     * our own outage would look like every region's.
     */
    Decision evaluate(Instant now) {
        Map<String, Outage> open = new LinkedHashMap<>();
        for (Outage o : recent(now)) {
            if (o.endedAt() == null) {
                open.put(o.region(), o);
            }
        }
        Map<String, RegionRates.Rates> all = new LinkedHashMap<>();
        for (String region : rates.regions()) {
            all.put(region, rates.read(region, now, props.outageRecentWindow(), props.outageBaselineWindow()));
        }
        Map<String, Object> silent = jdbc.query("SELECT details::text FROM presence_latch WHERE name = ?",
                (rs, n) -> Json.readMap(rs.getString(1)), FLEET_SILENT).stream().findFirst().orElse(null);
        if (silent != null) {
            boolean back = silent.entrySet().stream().anyMatch(e -> all.containsKey(e.getKey())
                    && all.get(e.getKey()).recentPerSec() >= props.outageRecoverShare() * ((Number) e.getValue()).doubleValue());
            if (!back) {
                return new Decision(Set.of(), true);
            }
            jdbc.update("DELETE FROM presence_latch WHERE name = ?", FLEET_SILENT);
            log.info("Traffic is back after a silence across every region");
        }
        Map<String, RegionRates.Rates> judged = new LinkedHashMap<>();
        List<String> collapsed = new ArrayList<>();
        for (Map.Entry<String, RegionRates.Rates> entry : all.entrySet()) {
            String region = entry.getKey();
            RegionRates.Rates r = entry.getValue();
            Outage current = open.get(region);
            if (current != null) {
                if (r.recentPerSec() >= props.outageRecoverShare() * current.baselinePerSec()) {
                    end(current, r, now);
                    open.remove(region);
                }
                continue;
            }
            boolean enoughHistory = r.baselineBucketsWithTraffic() >= Math.max(3, r.baselineBuckets() * 0.8);
            if (enoughHistory && r.baselinePerSec() >= props.outageMinRatePerSec()) {
                judged.put(region, r);
                if (r.recentPerSec() < props.outageDropShare() * r.baselinePerSec()) {
                    collapsed.add(region);
                }
            }
        }
        boolean fleetSilent = open.isEmpty() && judged.size() >= 2 && collapsed.size() == judged.size();
        if (fleetSilent) {
            Map<String, Double> baselines = new LinkedHashMap<>();
            judged.forEach((region, r) -> baselines.put(region, r.baselinePerSec()));
            jdbc.update("""
                    INSERT INTO presence_latch (name, details) VALUES (?, ?::jsonb)
                    ON CONFLICT (name) DO UPDATE SET details = EXCLUDED.details, set_at = now()
                    """, FLEET_SILENT, Json.write(baselines));
            log.warn("Every region went quiet at once ({}); treating it as a platform or network-wide problem, not a regional outage",
                    collapsed);
        } else {
            for (String region : collapsed) {
                start(region, judged.get(region), now).ifPresent(o -> open.put(region, o));
            }
        }
        openGauge.set(open.size());
        Set<String> covered = new HashSet<>();
        for (Outage o : recent(now)) {
            covered.add(o.region());
        }
        return new Decision(covered, fleetSilent);
    }

    /** Open outages, and those that ended within the settle period. */
    private List<Outage> recent(Instant now) {
        return jdbc.query("""
                SELECT outage_id, region, started_at, ended_at, baseline_per_sec FROM region_outage
                WHERE home_region = ? AND (ended_at IS NULL OR ended_at > ?)
                """, (rs, n) -> new Outage(rs.getObject("outage_id", UUID.class), rs.getString("region"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(),
                rs.getDouble("baseline_per_sec")), props.region(), Timestamp.from(now.minus(props.outageSettle())));
    }

    private java.util.Optional<Outage> start(String region, RegionRates.Rates r, Instant now) {
        UUID outageId = UUID.randomUUID();
        List<CustomerNotice> sent = tx.execute(status -> {
            int inserted = jdbc.update("""
                    INSERT INTO region_outage (outage_id, region, home_region, started_at, baseline_per_sec, lines_in_region)
                    VALUES (?, ?, ?, ?, ?, 0)
                    ON CONFLICT (region, home_region) WHERE ended_at IS NULL DO NOTHING
                    """, outageId, region, props.region(), Timestamp.from(now), r.baselinePerSec());
            if (inserted == 0) {
                return null; // another instance declared it first
            }
            List<CustomerNotice> out = new ArrayList<>();
            int[] total = {0};
            jdbc.query("""
                    SELECT d.customer_id, count(*) AS lines
                    FROM device_state d JOIN line l ON l.iccid = d.iccid
                    WHERE d.region = ? AND l.home_region = ? AND l.line_state = 'ACTIVE'
                    GROUP BY d.customer_id ORDER BY d.customer_id
                    """, rs -> {
                int lines = rs.getInt("lines");
                total[0] += lines;
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("outageId", outageId.toString());
                details.put("region", region);
                details.put("startedAt", now.toString());
                details.put("linesInRegion", lines);
                out.add(CustomerNoticeOutbox.notice(rs.getString("customer_id"), CustomerNotice.Kind.OUTAGE_STARTED, now, details));
            }, region, props.region());
            jdbc.update("UPDATE region_outage SET lines_in_region = ? WHERE outage_id = ?", total[0], outageId);
            notices.insert(out);
            return out;
        });
        if (sent == null) {
            return java.util.Optional.empty();
        }
        notices.publishCommitted(sent);
        log.warn("Outage in region {}: {} events/s against a usual {} events/s; {} customers notified instead of per-line alerts",
                region, String.format("%.2f", r.recentPerSec()), String.format("%.2f", r.baselinePerSec()), sent.size());
        return java.util.Optional.of(new Outage(outageId, region, now, null, r.baselinePerSec()));
    }

    private void end(Outage outage, RegionRates.Rates r, Instant now) {
        List<CustomerNotice> sent = tx.execute(status -> {
            int updated = jdbc.update("UPDATE region_outage SET ended_at = ? WHERE outage_id = ? AND ended_at IS NULL",
                    Timestamp.from(now), outage.outageId());
            if (updated == 0) {
                return null; // another instance ended it
            }
            Map<String, Integer> stillOffline = new LinkedHashMap<>();
            jdbc.query("""
                    SELECT d.customer_id, count(*) AS lines
                    FROM device_state d JOIN line l ON l.iccid = d.iccid
                    WHERE d.region = ? AND l.home_region = ? AND d.connection = 'OFFLINE' AND d.connection_reason = ?
                      AND d.connection_since >= ?
                    GROUP BY d.customer_id
                    """, rs -> {
                stillOffline.put(rs.getString("customer_id"), rs.getInt("lines"));
            }, outage.region(), props.region(), Connection.REASON_REGION_OUTAGE, Timestamp.from(outage.startedAt()));
            jdbc.update("UPDATE region_outage SET lines_still_offline = ? WHERE outage_id = ?",
                    stillOffline.values().stream().mapToInt(Integer::intValue).sum(), outage.outageId());
            List<CustomerNotice> out = new ArrayList<>();
            jdbc.query("""
                    SELECT customer_id FROM customer_notice
                    WHERE kind = 'OUTAGE_STARTED' AND details->>'outageId' = ? ORDER BY customer_id
                    """, rs -> {
                String customerId = rs.getString("customer_id");
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("outageId", outage.outageId().toString());
                details.put("region", outage.region());
                details.put("startedAt", outage.startedAt().toString());
                details.put("endedAt", now.toString());
                details.put("linesStillOffline", stillOffline.getOrDefault(customerId, 0));
                out.add(CustomerNoticeOutbox.notice(customerId, CustomerNotice.Kind.OUTAGE_ENDED, now, details));
            }, outage.outageId().toString());
            notices.insert(out);
            return out;
        });
        if (sent == null) {
            return;
        }
        notices.publishCommitted(sent);
        log.warn("Outage in region {} ended: {} events/s against {} events/s before it; {} customers notified",
                outage.region(), String.format("%.2f", r.recentPerSec()), String.format("%.2f", outage.baselinePerSec()),
                sent.size());
    }
}
