# iot-telemetry-pipeline: Open Questions

**Status:** closed on 2026-11-04. No answers had arrived when the build started, so each question was settled on its recommended option. The **[assumption, Qn]** markers in the other documents now refer to these decisions. Any of them can still be reopened.

| # | Question | Decision |
|---|---|---|
| 1 | **Tenants.** Do devices belong to tenants, with rate limits per tenant as well as per device? | Yes |
| 2 | **Offline detection (FR-13).** Should the platform mark devices offline when heartbeats stop? | Yes, with the outage guard in FR-20 |
| 3 | **What PostgreSQL stores.** Status changes only, a short raw-event window, or aggregates? | Latest status, status changes for 90 days, and raw telemetry for 48 hours in hourly partitions. Kafka keeps the 7-day raw record for replay. |
| 4 | **Legacy system.** Build a legacy poller and a cut-over story, or only the new pipeline? | Only the new pipeline; the cut-over is documented in Phase 8 |
| 5 | **Capacity inputs.** 5 million devices, 500-byte events, 15,000 events/sec average? | Kept: about one event per device every 5.5 minutes |
| 6 | **Multi-region.** Design and simulate, or design only? | Designed now, simulated with two local clusters in Phase 6 |
