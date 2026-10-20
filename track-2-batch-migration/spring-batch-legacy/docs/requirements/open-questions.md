# spring-batch-legacy: Open Questions

Decisions needed before the data layer design. Until each one is decided, the requirements use the **working assumption** shown and mark it **[assumption]**.

1. **Domain.** Is customer accounts and monthly billing the right domain for the synthetic data?
   **Working assumption:** yes. It has a clean natural key and uses every awkward COBOL field type.
2. **Full snapshot or changes only.** Does each daily file contain every record, or only records that changed? This decides whether we compare hashes to skip unchanged records.
   **Working assumption:** full snapshot, with a per-record hash so only real changes are written. A snapshot also reveals accounts that have disappeared.
3. **Parallel strategy.** Multi-threaded step, local partitioning, or remote partitioning across workers?
   **Working assumption:** local partitioning by record range first, with remote partitioning designed and documented. Fixed-length records mean partition N starts at byte N × 250.
4. **Idempotency mechanism.** Upsert on the account number, a ledger of file and record position, or both?
   **Working assumption:** upsert on the account number, plus the last-written and last-seen file ID on each row. Rollback is a reload of the last good snapshot, because a ledger alone cannot restore old values.
5. **Parity proof.** Counts and per-column checksums, or a full field-by-field comparison?
   **Working assumption:** counts, control totals and a per-record hash comparison on every run, which covers 100% of records. Field-by-field comparison runs only on hash mismatches. Test runs also compare against expected values from the generator.
6. **Throughput target.** Raise the design target to about 10,000 records/sec so that one restart still fits inside the 2-hour window?
   **Working assumption:** yes. See the window budget in the non-functional requirements.
7. **Full-volume run.** Attempt one full 50-million-record run on the laptop if disk allows, or benchmark at 5 million and project?
   **Working assumption:** benchmark at 5 million, then attempt one full run with change capture off for the initial load.
8. **Missing accounts.** When an account is absent from today's snapshot, should it be flagged as missing, or deleted?
   **Working assumption:** flag it with `missing_since` and never delete. Fail the job if more than 1% of accounts go missing in one file.
9. **First load and the change stream.** Should the initial 50-million-row load be published to Kafka?
   **Working assumption:** no. Consumers start from a database snapshot, and the stream carries changes from the second load onwards.
10. **Last-seen tracking cost.** Should unchanged rows record the file that last saw them, given that this is a write on every row?
    **Working assumption:** yes, but as one set-based step after loading rather than per row. To be measured in phase 1.
