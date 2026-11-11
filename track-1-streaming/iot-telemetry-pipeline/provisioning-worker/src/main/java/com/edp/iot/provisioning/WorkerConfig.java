package com.edp.iot.provisioning;

import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.common.request.RequestStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
class WorkerConfig {

    @Bean
    RequestStore requestStore(JdbcTemplate jdbc) {
        return new RequestStore(jdbc);
    }

    @Bean
    LineChangeOutbox lineChangeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                                      ProvisioningProperties props) {
        return new LineChangeOutbox(jdbc, kafka, tx, props.publishTimeout());
    }

    @Bean
    HttpClient networkHttpClient() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    }
}
