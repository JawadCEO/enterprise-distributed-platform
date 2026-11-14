# iot-telemetry-pipeline: Decisions

The domain and the end-to-end picture are agreed: a carrier IoT connectivity platform, described in [how-it-works.md](how-it-works.md). These questions were open at the start. Questions 1 to 7 were decided as recommended and built that way; the documents mark what follows from each as **[decided, Qn]**. Question 8 was not carried out as planned; see its note.

1. **Customers and rate limits.** Lines belong to business customers, and rate limits apply per customer as well as per line. Recommendation: yes.
2. **Offline alerts.** Report a line offline when it detaches or its keep-alives stop, but not for expected silence (suspended, profile switch) and not line by line during a regional outage. Recommendation: yes.
3. **What PostgreSQL stores.** Current state for every line; 90 days of history (state changes, attach/detach, requests); 13 months of usage; keep-alives and routine readings only update current state and aren't stored row by row. Kafka keeps 7 days of raw events for replay. Recommendation: yes. The alternative, storing every raw event for 7 days, is about 9 billion rows.
4. **Legacy poller.** Build only the new platform and document the cut-over (run both, compare, switch). Recommendation: yes. **Decided:** see the [cut-over plan](../operations/cut-over.md).
5. **Capacity.** 5 million lines, ~500-byte events, 15,000 events/sec on average and 50,000 at peak; laptop pass target 5,000/sec. Recommendation: keep these.
6. **Multi-region.** Design it fully now and simulate two regions locally in Phase 6. Recommendation: yes. **Decided:** see [scale and multi-region](../results/carrier-5-scale-2026-11-11.md).
7. **Remote profile switch (Flow E).** Include it as P2, built only if time allows. Recommendation: yes, P2. **Decided:** built in full; see [features](../results/carrier-features-2026-11-13.md).
8. **Two PoCs before Phase 0 closes.** Polling vs push (requests/sec, detection delay) and HTTP vs MQTT ingestion (throughput per core, behaviour when 10% of devices disconnect at once). Recommendation: yes. **Not run as separate PoCs.** The full build answered the first in practice: customers get webhooks within milliseconds instead of polling (event to webhook p50 23 to 89 ms in the part 6 runs). HTTP versus MQTT was not measured; ingestion is HTTP only (ADR-003), and an MQTT adapter remains possible.
