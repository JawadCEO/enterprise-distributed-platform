package com.edp.iot.api.requests;

import com.edp.iot.common.Topics;
import com.edp.iot.common.change.LineChangeOutbox;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(RequestProperties.class)
class RequestConfig {

    @Bean
    LineChangeOutbox lineChangeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                                      RequestProperties props) {
        return new LineChangeOutbox(jdbc, kafka, tx, props.publishTimeout());
    }

    @Bean
    KafkaAdmin.NewTopics requestTopics(RequestProperties props) {
        return new KafkaAdmin.NewTopics(TopicBuilder.name(Topics.LINE_COMMANDS)
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .config("retention.ms", String.valueOf(Duration.ofDays(7).toMillis()))
                .config("min.insync.replicas", String.valueOf(props.minInsyncReplicas()))
                .build());
    }
}
