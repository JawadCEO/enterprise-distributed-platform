package com.edp.iot.presence;

import com.edp.iot.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

@Configuration
class PresenceTopics {

    @Bean
    NewTopic customerNoticesTopic(PresenceProperties props) {
        return TopicBuilder.name(Topics.CUSTOMER_NOTICES)
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .config("min.insync.replicas", String.valueOf(props.minInsyncReplicas()))
                .config("retention.ms", String.valueOf(Duration.ofDays(7).toMillis()))
                .build();
    }
}
