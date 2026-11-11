package com.edp.iot.state.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * The processor's own signals. {@code iot.freshness} is NFR-2 measured directly: from event-ingest's
 * receive time to the moment the state is committed and cached, so readable.
 */
@Component("stateProcessorMetrics") // "processorMetrics" clashes with Spring Boot's CPU metrics bean
public class StateMetrics {

    private final Timer freshness;
    private final Counter applied;
    private final Counter skipped;
    private final Counter deadLettered;
    private final Counter retried;
    private final Counter changes;
    private final Counter usageCounted;
    private final DistributionSummary batchSize;

    public StateMetrics(MeterRegistry registry) {
        this.freshness = Timer.builder("iot.freshness")
                .description("event-ingest receive to state readable")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(150))
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
        this.applied = Counter.builder("iot.state.events").tag("outcome", "applied").register(registry);
        this.skipped = Counter.builder("iot.state.events").tag("outcome", "duplicate_or_stale").register(registry);
        this.deadLettered = Counter.builder("iot.state.events").tag("outcome", "dead_letter").register(registry);
        this.retried = Counter.builder("iot.state.events").tag("outcome", "retry").register(registry);
        this.changes = Counter.builder("iot.state.line.changes").register(registry);
        this.usageCounted = Counter.builder("iot.state.usage.records").tag("outcome", "counted").register(registry);
        this.batchSize = DistributionSummary.builder("iot.state.batch.size").publishPercentiles(0.5, 0.99).register(registry);
    }

    void batch(int size) {
        batchSize.record(size);
    }

    void committed(Collection<Instant> receivedAt, int appliedCount, int validCount, int changeCount, int usageCount) {
        Instant now = Instant.now();
        for (Instant at : receivedAt) {
            freshness.record(Duration.between(at, now));
        }
        applied.increment(appliedCount);
        skipped.increment(validCount - appliedCount);
        changes.increment(changeCount);
        usageCounted.increment(usageCount);
    }

    void deadLettered(int count) {
        deadLettered.increment(count);
    }

    void retried() {
        retried.increment();
    }
}
