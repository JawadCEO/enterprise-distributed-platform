package com.edp.iot.notifier;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.notice.CustomerNotice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

    ChangeListener(NotificationWriter writer, Deliverer deliverer) {
        this.writer = writer;
        this.deliverer = deliverer;
    }

    @KafkaListener(topics = {Topics.LINE_CHANGES, Topics.CUSTOMER_NOTICES}, groupId = "notifier", batch = "true",
            concurrency = "${iot.notifier.concurrency:2}")
    void onBatch(List<ConsumerRecord<String, String>> records) {
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
