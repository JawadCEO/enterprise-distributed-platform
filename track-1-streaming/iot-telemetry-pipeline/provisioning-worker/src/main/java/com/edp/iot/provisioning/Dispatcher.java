package com.edp.iot.provisioning;

import com.edp.iot.common.request.LineCommand;
import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestStatus;
import com.edp.iot.common.request.RequestStore;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Carries out commands against the network (FR-7): paced, retried with exponential back-off until
 * the network accepts the command or the request's deadline passes. Retries live in memory; if
 * this instance dies the sweeper fails the request at its deadline, so nothing stays pending (FR-8).
 */
@Component
class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);

    private final NetworkClient network;
    private final RequestStore store;
    private final Finisher finisher;
    private final Pacer pacer;
    private final ProvisioningProperties props;
    private final MeterRegistry meters;
    private final ScheduledExecutorService executor;

    Dispatcher(NetworkClient network, RequestStore store, Finisher finisher, Pacer pacer, ProvisioningProperties props,
               MeterRegistry meters) {
        this.network = network;
        this.store = store;
        this.finisher = finisher;
        this.pacer = pacer;
        this.props = props;
        this.meters = meters;
        this.executor = Executors.newScheduledThreadPool(props.dispatchThreads());
    }

    void dispatch(LineCommand command) {
        executor.execute(() -> attempt(command, 0));
    }

    private void attempt(LineCommand command, int attempt) {
        try {
            if (Instant.now().isAfter(command.deadlineAt()) || !store.isPending(command.requestId())) {
                return; // finished already, or the sweeper will fail it
            }
            long wait = pacer.tryAcquire();
            if (wait > 0) {
                executor.schedule(() -> attempt(command, attempt), wait, TimeUnit.MILLISECONDS);
                return;
            }
            NetworkClient.Answer answer = network.provision(command);
            meters.counter("iot.provisioning.calls", "outcome", answer.outcome().name().toLowerCase()).increment();
            switch (answer.outcome()) {
                case ACCEPTED -> store.markNetworkAccepted(command.requestId(), Instant.now());
                case REFUSED -> {
                    log.info("Network refused request {} ({}); failing it", command.requestId(), answer.detail());
                    finisher.fail(List.of(asRequest(command)), RequestStore.REASON_NETWORK_REJECTED);
                }
                case RETRY -> {
                    long backoff = Math.min(props.retryMax().toMillis(), props.retryInitial().toMillis() << Math.min(attempt, 10));
                    log.debug("Network call for {} failed ({}); retry {} in {} ms", command.requestId(), answer.detail(),
                            attempt + 1, backoff);
                    executor.schedule(() -> attempt(command, attempt + 1), backoff, TimeUnit.MILLISECONDS);
                }
            }
        } catch (RuntimeException e) {
            // the database blipped: try again shortly; the deadline still bounds it
            log.warn("Dispatch of {} failed: {}", command.requestId(), e.toString());
            executor.schedule(() -> attempt(command, attempt + 1), props.retryMax().toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** Enough of the request for {@link Finisher}, which re-reads and locks the real row. */
    private static LineRequest asRequest(LineCommand c) {
        return new LineRequest(c.requestId(), c.customerId(), c.iccid(), c.action(), RequestStatus.PENDING, null, null,
                null, c.deadlineAt(), null);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
