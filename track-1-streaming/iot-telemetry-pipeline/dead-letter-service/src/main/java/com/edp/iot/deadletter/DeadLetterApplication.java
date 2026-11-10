package com.edp.iot.deadletter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DeadLetterApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeadLetterApplication.class, args);
    }
}
