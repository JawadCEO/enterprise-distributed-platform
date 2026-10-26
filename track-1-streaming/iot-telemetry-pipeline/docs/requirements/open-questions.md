# iot-telemetry-pipeline: Open Questions

The domain and the end-to-end picture are agreed: a carrier IoT connectivity platform, described in [how-it-works.md](how-it-works.md). Until a question below is answered, the documents use its recommendation as a working assumption, marked **[assumption, Qn]**.

1. **Customers and rate limits.** Lines belong to business customers, and rate limits apply per customer as well as per line. Recommendation: yes.
2. **Offline alerts.** Report a line offline when it detaches or its keep-alives stop, but not for expected silence (suspended, profile switch) and not line by line during a regional outage. Recommendation: yes.
3. **What PostgreSQL stores.** Current state for every line; 90 days of history (state changes, attach/detach, requests); 13 months of usage; keep-alives and routine readings only update current state and aren't stored row by row. Kafka keeps 7 days of raw events for replay. Recommendation: yes. The alternative, storing every raw event for 7 days, is about 9 billion rows.
4. **Legacy poller.** Build only the new platform and document the cut-over (run both, compare, switch). Recommendation: yes.
5. **Capacity.** 5 million lines, ~500-byte events, 15,000 events/sec on average and 50,000 at peak; laptop pass target 5,000/sec. Recommendation: keep these.
6. **Multi-region.** Design it fully now and simulate two regions locally in Phase 6. Recommendation: yes.
7. **Remote profile switch (Flow E).** Include it as P2, built only if time allows. Recommendation: yes, P2.
8. **Two PoCs before Phase 0 closes.** Polling vs push (requests/sec, detection delay) and HTTP vs MQTT ingestion (throughput per core, behaviour when 10% of devices disconnect at once). Recommendation: yes.
