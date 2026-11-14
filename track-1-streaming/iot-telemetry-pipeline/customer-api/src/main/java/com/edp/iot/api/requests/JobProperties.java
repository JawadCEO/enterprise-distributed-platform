package com.edp.iot.api.requests;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for bulk jobs ({@code iot.jobs}, FR-9).
 *
 * @param maxLines      most lines in one job
 * @param maxInFlight   most requests of one job waiting on the network at once. With the network
 *                      paced by provisioning-worker, this bounds how long a job's request waits for
 *                      its turn (well inside the request's time limit) and how much of the network
 *                      one job can hold while other customers' requests are waiting
 * <p>The runner feeds each job every {@code iot.jobs.tick-ms} (default 1000).
 *
 * @param lease         how long one customer-api instance holds a job between ticks; another
 *                      instance takes over a job whose lease ran out
 */
@ConfigurationProperties("iot.jobs")
public record JobProperties(
        @DefaultValue("50000") int maxLines,
        @DefaultValue("200") int maxInFlight,
        @DefaultValue("30s") Duration lease) {
}
