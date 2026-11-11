package com.edp.iot.provisioning;

import com.edp.iot.common.change.LineChange;
import com.edp.iot.common.change.LineChangeOutbox;
import com.edp.iot.common.line.LineState;
import com.edp.iot.common.request.LineRequest;
import com.edp.iot.common.request.RequestStatus;
import com.edp.iot.common.request.RequestStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fails requests from this worker: the network refused them, or they ran out of time. */
@Component
class Finisher {

    private final RequestStore store;
    private final LineChangeOutbox outbox;
    private final TransactionTemplate tx;

    Finisher(RequestStore store, LineChangeOutbox outbox, TransactionTemplate tx) {
        this.store = store;
        this.outbox = outbox;
        this.tx = tx;
    }

    /**
     * Fails the given requests if they are still pending, putting each line back where it was.
     * Locks lines first, then requests, like every other writer. Returns how many it failed.
     */
    int fail(Collection<LineRequest> candidates, String reason) {
        if (candidates.isEmpty()) {
            return 0;
        }
        List<LineChange> changes = tx.execute(status -> {
            Map<String, LineState> lines = store.lockLines(candidates.stream().map(LineRequest::iccid).toList());
            Map<UUID, LineRequest> pending = store.lockPending(candidates.stream().map(LineRequest::requestId).toList());
            List<LineChange> out = new ArrayList<>();
            Instant now = Instant.now();
            for (LineRequest r : pending.values()) {
                out.addAll(store.finish(r, lines.get(r.iccid()), RequestStatus.FAILED, reason, null, now).changes());
            }
            outbox.insert(out);
            return out;
        });
        outbox.publishCommitted(changes);
        return (int) changes.stream().filter(c -> c.kind().name().startsWith("REQUEST_")).count();
    }
}
