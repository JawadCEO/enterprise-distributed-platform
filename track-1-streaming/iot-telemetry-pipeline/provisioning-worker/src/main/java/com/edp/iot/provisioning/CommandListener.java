package com.edp.iot.provisioning;

import com.edp.iot.common.Json;
import com.edp.iot.common.Topics;
import com.edp.iot.common.request.LineCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads {@code line.commands} (keyed by line, so commands for one line arrive in order) and hands
 * each one to the {@link Dispatcher}. A repeated command is harmless: the dispatcher skips requests
 * that are no longer pending, and the network de-duplicates on the request ID.
 */
@Component
class CommandListener {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final Dispatcher dispatcher;

    CommandListener(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @KafkaListener(topics = Topics.LINE_COMMANDS, groupId = "provisioning-worker", concurrency = "${iot.provisioning.concurrency:2}")
    void onCommand(ConsumerRecord<String, String> record) {
        LineCommand command;
        try {
            command = Json.read(record.value(), LineCommand.class);
        } catch (RuntimeException e) {
            log.error("Unreadable command at {}-{}@{}, skipped: {}", record.topic(), record.partition(), record.offset(),
                    e.toString());
            return;
        }
        dispatcher.dispatch(command);
    }
}
