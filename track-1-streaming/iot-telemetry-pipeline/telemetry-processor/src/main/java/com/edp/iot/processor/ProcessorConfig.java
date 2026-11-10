package com.edp.iot.processor;

import com.edp.iot.common.Topics;
import com.edp.iot.common.outbox.StatusChangeOutbox;
import com.edp.iot.common.registry.DeviceRegistry;
import com.edp.iot.processor.pipeline.FailureClassifier;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

@Configuration
class ProcessorConfig {

    @Bean
    DeviceRegistry deviceRegistry(JdbcTemplate jdbc, ProcessorProperties props) {
        return new DeviceRegistry(jdbc, props.registryCacheTtl(), props.registryCacheSize());
    }

    @Bean
    StatusChangeOutbox statusChangeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                                          ProcessorProperties props) {
        return new StatusChangeOutbox(jdbc, kafka, tx, props.publishTimeout());
    }

    /**
     * Batch listener for {@code telemetry.raw}. The listener handles per-event failures itself
     * (retry topics, dead letters) and only throws when a dependency is down. The error handler
     * then retries the same batch with backoff and never gives up, so events wait safely in Kafka
     * until PostgreSQL is back (DR table: database primary).
     */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> rawBatchFactory(ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(10_000);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(backOff));
        return factory;
    }

    /** Record listener with manual acks for the retry tiers, so a record can wait for its due time. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> retryFactory(ConsumerFactory<String, String> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxInterval(10_000);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        factory.setCommonErrorHandler(new DefaultErrorHandler(backOff));
        return factory;
    }

    @Bean
    FailureClassifier failureClassifier() {
        return new FailureClassifier();
    }

    @Bean
    KafkaAdmin.NewTopics processorTopics(ProcessorProperties props) {
        return new KafkaAdmin.NewTopics(
                topic(Topics.RETRY_5S, props, Duration.ofDays(1)),
                topic(Topics.RETRY_1M, props, Duration.ofDays(1)),
                topic(Topics.RETRY_10M, props, Duration.ofDays(1)),
                topic(Topics.DLQ, props, Duration.ofDays(7)),
                topic(Topics.STATUS_CHANGED, props, Duration.ofDays(7)));
    }

    private static NewTopic topic(String name, ProcessorProperties props, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .config("retention.ms", String.valueOf(retention.toMillis()))
                .config("min.insync.replicas", String.valueOf(props.minInsyncReplicas()))
                .build();
    }
}
