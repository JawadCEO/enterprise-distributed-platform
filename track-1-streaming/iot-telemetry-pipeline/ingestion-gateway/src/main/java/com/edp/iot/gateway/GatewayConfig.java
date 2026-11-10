package com.edp.iot.gateway;

import com.edp.iot.common.Topics;
import com.edp.iot.common.registry.DeviceRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

@Configuration
class GatewayConfig {

    @Bean
    DeviceRegistry deviceRegistry(JdbcTemplate jdbc, GatewayProperties props) {
        return new DeviceRegistry(jdbc, props.registryCacheTtl(), props.registryCacheSize());
    }

    @Bean
    NewTopic rawTopic(GatewayProperties props) {
        return TopicBuilder.name(Topics.RAW)
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .config("retention.ms", String.valueOf(Duration.ofDays(7).toMillis()))
                .config("min.insync.replicas", String.valueOf(props.minInsyncReplicas()))
                .build();
    }
}
