package com.edp.iot.notifier;

import com.edp.iot.common.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class NotifierConfig {

    /** Created here as well as by presence-monitor, so whichever starts first makes it. */
    @Bean
    NewTopic customerNoticesTopic(NotifierProperties props) {
        return TopicBuilder.name(Topics.CUSTOMER_NOTICES)
                .partitions(props.partitions())
                .replicas(props.replicationFactor())
                .config("min.insync.replicas", String.valueOf(props.minInsyncReplicas()))
                .config("retention.ms", String.valueOf(Duration.ofDays(7).toMillis()))
                .build();
    }

    /**
     * A batch that can't be stored (the database is down) is retried without end, so the offset
     * never moves past a change that has no notification row yet.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2);
        backOff.setMaxInterval(30_000);
        backOff.setMaxElapsedTime(Long.MAX_VALUE);
        return new DefaultErrorHandler(backOff);
    }

    @Bean
    HttpClient webhookHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
