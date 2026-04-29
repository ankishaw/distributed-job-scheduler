package com.jobscheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point — keep minimal.
 *
 * @EnableScheduling activates @Scheduled on HeartbeatService and StaleJobReclaimer.
 * @EnableConfigurationProperties wires AppProperties (@ConfigurationProperties).
 */
@SpringBootApplication
@EnableScheduling
public class JobSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobSchedulerApplication.class, args);
    }
}
