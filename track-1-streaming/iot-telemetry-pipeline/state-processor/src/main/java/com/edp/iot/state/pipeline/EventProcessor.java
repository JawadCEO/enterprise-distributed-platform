package com.edp.iot.state.pipeline;

import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.common.deadletter.ReasonCodes;
import com.edp.iot.common.event.EventType;
import com.edp.iot.common.event.NetworkEvent;
import com.edp.iot.common.line.LineInfo;
import com.edp.iot.common.line.LineRegistry;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.presence.PresenceKeys;
import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestStatus;
import com.edp.iot.common.request.RequestStore;
import com.edp.iot.common.state.DeviceState;
import com.edp.iot.state.StateProperties;
import com.edp.iot.state.model.LineModel;
import com.edp.iot.state.model.SecurityEvent;
import com.edp.iot.state.model.UsageCycle;
import com.edp.iot.state.store.StateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies a batch of events (data.md section 3, stages 3 to 8). One transaction per batch:
 * <ol>
 *   <li>drop events for lines that were deleted or deactivated after their ack (dead letter)</li>
 *   <li>drop events already processed (Redis duplicate keys, ADR-006)</li>
 *   <li>lock the batch's lines in a stable order, then load their device state and usage cycles</li>
 *   <li>finish the requests named by {@code PROVISIONING_RESULT} events (data.md section 4, stage 4)</li>
 *   <li>insert usage records keyed by event ID; only new ones count toward the total</li>
 *   <li>fold each line's events through {@link LineModel} and write state, usage, line state,
 *       security events and history (the history rows are the outbox for {@code line.changes})</li>
 * </ol>
 * After commit it updates Redis and publishes the changes. A failure before commit leaves nothing
 * behind, so the whole batch can be retried.
 */
@Component
public class EventProcessor {

    public record DeadLetter(NetworkEvent event, String reasonCode, String reason) {
    }

    public record Result(int applied, List<LineChange> changes, List<DeadLetter> deadLetters) {
    }

    private record Committed(List<DeviceState> states, List<LineChange> changes, int applied, int usageCounted,
                             Set<String> lineStateChanged, List<DeadLetter> deadLetters) {
    }

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final LineRegistry registry;
    private final StateStore store;
    private final RequestStore requests;
    private final RedisState redis;
    private final LineChangeOutbox outbox;
    private final TransactionTemplate tx;
    private final StateProperties props;
    private final StateMetrics metrics;
    private final ObjectProvider<Tracer> tracer;

    public EventProcessor(LineRegistry registry, StateStore store, RequestStore requests, RedisState redis,
                          LineChangeOutbox outbox, TransactionTemplate tx, StateProperties props, StateMetrics metrics,
                          ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
        this.registry = registry;
        this.store = store;
        this.requests = requests;
        this.redis = redis;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
    }

    public Result process(List<NetworkEvent> events) {
        metrics.pickedUp(events.stream().map(NetworkEvent::receivedAt).toList());
        Map<String, LineInfo> lines = registry.findAll(events.stream().map(NetworkEvent::lineId).distinct().toList());

        List<NetworkEvent> valid = new ArrayList<>(events.size());
        List<DeadLetter> deadLetters = new ArrayList<>();
        for (NetworkEvent e : events) {
            LineInfo line = lines.get(e.lineId());
            if (line == null) {
                deadLetters.add(new DeadLetter(e, ReasonCodes.UNKNOWN_LINE, "Line is not registered"));
            } else if (line.isDeactivated()) {
                deadLetters.add(new DeadLetter(e, ReasonCodes.INACTIVE_LINE, "Line was deactivated"));
            } else {
                valid.add(e);
            }
        }
        metrics.deadLettered(deadLetters.size());
        if (valid.isEmpty()) {
            return new Result(0, List.of(), deadLetters);
        }

        Set<UUID> seen = new HashSet<>(redis.alreadySeen(valid));
        Map<String, List<NetworkEvent>> byLine = new LinkedHashMap<>();
        List<NetworkEvent> fresh = new ArrayList<>(valid.size());
        for (NetworkEvent e : valid) {
            // seen.add also drops a second copy of the same event within this batch
            if (seen.add(e.eventId())) {
                fresh.add(e);
                byLine.computeIfAbsent(e.lineId(), k -> new ArrayList<>()).add(e);
            }
        }
        if (fresh.isEmpty()) {
            metrics.committed(List.of(), 0, valid.size(), 0, 0);
            return new Result(0, List.of(), deadLetters);
        }

        Committed committed = traced("postgres commit", () -> metrics.database().record(() -> tx.execute(status -> {
            Map<String, LineState> lineStates = store.lockLines(byLine.keySet());
            List<LineChange> changes = new ArrayList<>();
            Set<String> lineStateChanged = new HashSet<>();
            resolveRequests(fresh, lineStates, changes, lineStateChanged);
            List<DeadLetter> deactivated = dropDeactivated(byLine, lineStates);
            Map<String, DeviceState> states = store.loadStates(byLine.keySet());
            List<NetworkEvent> usage = byLine.values().stream().flatMap(List::stream)
                    .filter(e -> e.type() == EventType.USAGE_RECORD).toList();
            Set<LocalDate> cycleStarts = new HashSet<>();
            usage.forEach(e -> cycleStarts.add(UsageCycle.cycleOf(e.networkTime())));
            Map<String, Map<LocalDate, UsageCycle>> cycles = store.lockCycles(
                    usage.stream().map(NetworkEvent::lineId).distinct().toList(), cycleStarts);
            Set<UUID> counted = store.insertUsage(usage);

            List<DeviceState> stateUpdates = new ArrayList<>();
            List<UsageCycle> cycleUpdates = new ArrayList<>();
            Map<String, LineState> lineStateUpdates = new HashMap<>();
            List<SecurityEvent> security = new ArrayList<>();
            int applied = 0;
            for (Map.Entry<String, List<NetworkEvent>> entry : byLine.entrySet()) {
                String iccid = entry.getKey();
                LineInfo line = lines.get(iccid);
                LineState current = lineStates.getOrDefault(iccid, line.lineState());
                LineModel.Result r = LineModel.apply(
                        new LineModel.Snapshot(line, current, states.get(iccid), cycles.get(iccid)),
                        entry.getValue(), counted);
                if (r.state() != null) {
                    stateUpdates.add(r.state());
                }
                cycleUpdates.addAll(r.cycles().values());
                if (r.lineState() != null && current.canMoveTo(r.lineState())) {
                    lineStateUpdates.put(iccid, r.lineState());
                }
                security.addAll(r.securityEvents());
                changes.addAll(r.changes());
                applied += r.applied();
            }
            store.upsertStates(stateUpdates);
            store.upsertCycles(cycleUpdates);
            store.updateLineStates(lineStateUpdates);
            store.insertSecurityEvents(security);
            outbox.insert(changes);
            lineStateChanged.addAll(lineStateUpdates.keySet());
            return new Committed(stateUpdates, changes, applied, counted.size(), lineStateChanged, deactivated);
        })));

        deadLetters.addAll(committed.deadLetters());
        metrics.deadLettered(committed.deadLetters().size());
        committed.lineStateChanged().forEach(registry::invalidate);
        traced("redis update", () -> {
            metrics.cache().record(() ->
                    redis.afterCommit(fresh, committed.states(), deadlines(byLine, lines), regionCounts(byLine, committed.states())));
            return null;
        });
        metrics.committed(fresh.stream().map(NetworkEvent::receivedAt).toList(), committed.applied(), valid.size(),
                committed.changes().size(), committed.usageCounted());
        outbox.publishCommitted(committed.changes());
        return new Result(committed.applied(), committed.changes(), deadLetters);
    }

    /** Runs {@code work} in a child span of the batch span, so a trace shows the database and Redis hops (NFR-20). */
    private <T> T traced(String name, java.util.function.Supplier<T> work) {
        Tracer t = tracer.getIfAvailable();
        if (t == null) {
            return work.get();
        }
        Span span = t.nextSpan().name(name).start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    /**
     * Finishes the pending request each {@code PROVISIONING_RESULT} names, moving the line to the
     * action's target state ({@code DONE}) or back to where it was ({@code REJECTED}). A result for
     * a request that is no longer pending (the sweeper already timed it out, or a repeat) changes
     * nothing. Updates {@code lineStates} so the line model sees the new state.
     */
    private void resolveRequests(List<NetworkEvent> fresh, Map<String, LineState> lineStates, List<LineChange> changes,
                                 Set<String> lineStateChanged) {
        Map<UUID, NetworkEvent> byRequest = new LinkedHashMap<>();
        for (NetworkEvent e : fresh) {
            if (e.type() == EventType.PROVISIONING_RESULT && e.payload().get(NetworkEvent.REQUEST_ID) instanceof String id) {
                byRequest.putIfAbsent(UUID.fromString(id), e);
            }
        }
        if (byRequest.isEmpty()) {
            return;
        }
        Map<UUID, LineRequest> pending = requests.lockPending(byRequest.keySet());
        byRequest.forEach((requestId, e) -> {
            LineRequest r = pending.get(requestId);
            if (r == null || !r.iccid().equals(e.lineId())) {
                log.info("Provisioning result for request {} on {} matches no pending request; ignored", requestId, e.lineId());
                return;
            }
            boolean done = "DONE".equals(e.payload().get(NetworkEvent.OUTCOME));
            Object reason = e.payload().get(NetworkEvent.REASON);
            RequestStore.Finished f = requests.finish(r, lineStates.get(r.iccid()),
                    done ? RequestStatus.COMPLETED : RequestStatus.FAILED,
                    done ? null : reason instanceof String s ? s : RequestStore.REASON_NETWORK_REJECTED,
                    e.eventId(), e.receivedAt());
            lineStates.put(r.iccid(), f.lineState());
            changes.addAll(f.changes());
            lineStateChanged.add(r.iccid());
        });
    }

    /**
     * Events for a line the database shows as deactivated (newer than the registry cache, or
     * deactivated earlier in this batch) are dead-lettered instead of applied.
     */
    private static List<DeadLetter> dropDeactivated(Map<String, List<NetworkEvent>> byLine, Map<String, LineState> lineStates) {
        List<DeadLetter> out = new ArrayList<>();
        byLine.entrySet().removeIf(entry -> {
            if (lineStates.get(entry.getKey()) != LineState.DEACTIVATED) {
                return false;
            }
            entry.getValue().stream().filter(e -> e.type() != EventType.PROVISIONING_RESULT)
                    .forEach(e -> out.add(new DeadLetter(e, ReasonCodes.INACTIVE_LINE, "Line was deactivated")));
            return true;
        });
        return out;
    }

    /**
     * Signs of life per region the line was last seen in, bucketed by when event-ingest received
     * them, for the outage guard's rate counters (FR-21). A detach is not a sign of life; a line with
     * no known region yet is not counted.
     */
    private static RedisState.RegionCounts regionCounts(Map<String, List<NetworkEvent>> byLine, List<DeviceState> states) {
        Map<String, Long> buckets = new HashMap<>();
        Set<String> regions = new HashSet<>();
        for (DeviceState s : states) {
            List<NetworkEvent> lineEvents = byLine.get(s.iccid());
            if (s.region() == null || lineEvents == null) {
                continue;
            }
            for (NetworkEvent e : lineEvents) {
                if (e.type() != EventType.DEVICE_DETACHED && e.type() != EventType.PROVISIONING_RESULT) {
                    buckets.merge(PresenceKeys.regionBucket(s.region(), e.receivedAt().getEpochSecond()), 1L, Long::sum);
                    regions.add(s.region());
                }
            }
        }
        return new RedisState.RegionCounts(buckets, regions);
    }

    /**
     * Offline deadline per line: latest receive time of a sign of life in the batch + keep-alive
     * window + grace. A detach is not a sign of life.
     */
    private Map<String, Instant> deadlines(Map<String, List<NetworkEvent>> byLine, Map<String, LineInfo> lines) {
        Map<String, Instant> deadlines = new HashMap<>();
        byLine.forEach((iccid, lineEvents) -> lineEvents.stream()
                .filter(e -> e.type() != EventType.DEVICE_DETACHED)
                .map(NetworkEvent::receivedAt)
                .max(Instant::compareTo)
                .ifPresent(latest -> deadlines.put(iccid,
                        latest.plus(lines.get(iccid).keepAliveWindow()).plus(props.presenceGrace()))));
        return deadlines;
    }
}
