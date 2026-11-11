package com.edp.iot.ingest;

import com.edp.iot.common.Topics;
import com.edp.iot.common.line.LineRegistry;
import com.edp.iot.common.security.ApiKeys;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

@Configuration
class IngestConfig {

    @Bean
    LineRegistry lineRegistry(JdbcTemplate jdbc, IngestProperties props) {
        return new LineRegistry(jdbc, props.registryCacheTtl(), props.registryCacheSize());
    }

    @Bean
    ApiKeys apiKeys(JdbcTemplate jdbc) {
        return new ApiKeys(jdbc, Duration.ofSeconds(30));
    }

    @Bean
    NewTopic networkEventsTopic(IngestProperties props) {
        return TopicBuilder.name(Topics.NETWORK_EVENTS)
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .config("retention.ms", String.valueOf(Duration.ofDays(7).toMillis()))
                .config("min.insync.replicas", String.valueOf(props.minInsyncReplicas()))
                .build();
    }
}
