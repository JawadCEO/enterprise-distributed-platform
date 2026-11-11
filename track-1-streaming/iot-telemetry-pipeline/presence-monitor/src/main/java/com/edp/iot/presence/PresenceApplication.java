package com.edp.iot.presence;

import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.common.line.LineRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PresenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PresenceApplication.class, args);
    }

    @Bean
    LineRegistry lineRegistry(JdbcTemplate jdbc, PresenceProperties props) {
        return new LineRegistry(jdbc, props.registryCacheTtl(), 1_000_000);
    }

    @Bean
    LineChangeOutbox lineChangeOutbox(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx) {
        return new LineChangeOutbox(jdbc, kafka, tx, java.time.Duration.ofSeconds(5));
    }

    @Bean(destroyMethod = "close")
    AdminClient adminClient(KafkaAdmin kafkaAdmin) {
        return AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }
}
