package com.edp.iot.processor.pipeline;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * The processor's own signals (Phase 5). {@code iot.freshness} is NFR-2 measured directly: from
 * the gateway's receive time to the moment the status is committed and cached, so readable.
 */
@Component("telemetryProcessorMetrics") // "processorMetrics" clashes with Spring Boot's CPU metrics bean
public class ProcessorMetrics {

    private final Timer freshness;
    private final Counter stored;
    private final Counter duplicates;
    private final Counter deadLettered;
    private final Counter retried;
    private final Counter statusChanges;
    private final DistributionSummary batchSize;

    public ProcessorMetrics(MeterRegistry registry) {
        this.freshness = Timer.builder("iot.freshness")
                .description("Gateway receive to status readable")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(150))
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry);
        this.stored = Counter.builder("iot.processor.events").tag("outcome", "stored").register(registry);
        this.duplicates = Counter.builder("iot.processor.events").tag("outcome", "duplicate_or_expired").register(registry);
        this.deadLettered = Counter.builder("iot.processor.events").tag("outcome", "dead_letter").register(registry);
        this.retried = Counter.builder("iot.processor.events").tag("outcome", "retry").register(registry);
        this.statusChanges = Counter.builder("iot.processor.status.changes").register(registry);
        this.batchSize = DistributionSummary.builder("iot.processor.batch.size").publishPercentiles(0.5, 0.99).register(registry);
    }

    void batch(int size) {
        batchSize.record(size);
    }

    void committed(Collection<Instant> receivedAt, int storedCount, int validCount, int changes) {
        Instant now = Instant.now();
        for (Instant at : receivedAt) {
            freshness.record(Duration.between(at, now));
        }
        stored.increment(storedCount);
        duplicates.increment(validCount - storedCount);
        statusChanges.increment(changes);
    }

    void deadLettered(int count) {
        deadLettered.increment(count);
    }

    void retried() {
        retried.increment();
    }
}
