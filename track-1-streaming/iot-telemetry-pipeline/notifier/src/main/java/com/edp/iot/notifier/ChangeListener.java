package com.edp.iot.notifier;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.notice.CustomerNotice;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code line.changes} and {@code customer.notices} in batches and stores a notification for
 * each one the customer wants. The offsets move on only after the batch is stored, so a crash
 * re-reads it and the derived IDs make the second write a no-op.
 */
@Component
class ChangeListener {

    private static final Logger log = LoggerFactory.getLogger(ChangeListener.class);

    private final NotificationWriter writer;
    private final Deliverer deliverer;
    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    ChangeListener(NotificationWriter writer, Deliverer deliverer, ObjectProvider<Tracer> tracer,
                   ObjectProvider<Propagator> propagator) {
        this.writer = writer;
        this.deliverer = deliverer;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @KafkaListener(topics = {Topics.LINE_CHANGES, Topics.CUSTOMER_NOTICES}, groupId = "notifier", batch = "true",
            concurrency = "${iot.notifier.concurrency:2}")
    void onBatch(List<ConsumerRecord<String, String>> records) {
        Tracer t = tracer.getIfAvailable();
        Propagator p = propagator.getIfAvailable();
        if (t == null || p == null || records.isEmpty()) {
            store(records);
            return;
        }
        // Like state-processor: the batch span continues the trace of its first change, so a trace
        // runs from event-ingest through processing to the stored notification.
        Span span = p.extract(records.get(0).headers(), (headers, key) -> {
                    Header h = headers.lastHeader(key);
                    return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
                })
                .name("line.changes store notifications")
                .kind(Span.Kind.CONSUMER)
                .tag("messaging.batch.message_count", String.valueOf(records.size()))
                .start();
        try (Tracer.SpanInScope ignored = t.withSpan(span)) {
            store(records);
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private void store(List<ConsumerRecord<String, String>> records) {
        List<LineChange> changes = new ArrayList<>();
        List<CustomerNotice> notices = new ArrayList<>();
        for (ConsumerRecord<String, String> record : records) {
            try {
                if (Topics.CUSTOMER_NOTICES.equals(record.topic())) {
                    notices.add(Json.read(record.value(), CustomerNotice.class));
                } else {
                    LineChange change = Json.read(record.value(), LineChange.class);
                    if (change.notifyCustomer()) {
                        changes.add(change);
                    }
                }
            } catch (RuntimeException e) {
                log.error("Unreadable record at {}-{}@{}, skipped: {}", record.topic(), record.partition(), record.offset(),
                        e.toString());
            }
        }
        if (writer.write(changes, notices) > 0) {
            deliverer.wake();
        }
    }
}
