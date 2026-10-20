# iot-telemetry-pipeline: Open Questions

Decisions needed before the data layer design. Until they are answered, the requirement documents use each recommendation as a working assumption, marked **[assumption, Qn]**.

1. **Tenants.** I have assumed devices belong to tenants (customers or fleets) and that rate limits apply per tenant as well as per device. Correct? Recommendation: yes.
2. **Offline detection (FR-13).** Should the platform mark devices offline when heartbeats stop? It is the feature that most directly replaces polling. Recommendation: yes.
3. **What PostgreSQL stores.** Given 1.3 billion events a day, which is it: status changes only, a short raw-event window, or aggregates? Recommendation: latest status, status changes for 90 days, and raw telemetry for 48 hours in hourly partitions. Kafka keeps the 7-day raw record for replay. A 7-day window in PostgreSQL would be about 9 billion rows at full scale.
4. **Legacy system.** Do we include a small legacy poller and a cut-over story (run both, compare, switch), or only the new pipeline? Recommendation: only the new pipeline, with the migration approach documented.
5. **Capacity inputs.** Are 5 million devices, 500-byte events, and a 15,000 events/sec average reasonable for the story you want to tell? Recommendation: keep them. They work out to about one event per device every 5.5 minutes.
6. **Multi-region.** Design it fully and simulate two regions locally in a later phase, or design only? Recommendation: design now, simulate in Phase 6.
