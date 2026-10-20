# spring-batch-legacy: Non-Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md), or are targets still to be confirmed.

Every target below says how it is measured. A target without a measurement is not a requirement.

## 1. Performance

### 1.1 Window budget

The 2-hour window runs from file arrival to a `PASSED` reconciliation. The design target of 10,000 records/sec **[assumption: OQ-6]** gives this budget for 50 million records.

| Stage | Budget | Basis |
|---|---|---|
| Detect, register, integrity check (SHA-256 over 12.5GB) | ≤ 3 min | Sequential read at ≥ 100 MB/s |
| Decode, validate, upsert | ≤ 84 min | 50,000,000 ÷ 10,000/sec ≈ 83 min |
| Mark missing accounts | ≤ 5 min | One set-based statement over the target table |
| Reconciliation | ≤ 15 min | Parallel hash recompute at ≥ 55,000 rows/sec |
| **Planned total** | **≤ 107 min** | |
| Spare for one failure and restart | ≥ 13 min | Restart overhead (NFR-R2) plus rework of the open chunks |

At the original 7,000 records/sec the load alone takes 119 minutes, so no failure could ever be absorbed. That is why the design target is raised.

### 1.2 Targets

| ID | Area | Target | How it is measured |
|---|---|---|---|
| NFR-P1 | Throughput | ≥ 10,000 records/sec sustained over the load step | Records read ÷ load step time, from Spring Batch step metrics |
| NFR-P2 | Window | ≤ 120 min end to end for 50 million records | File arrival time to report time, from the manifest |
| NFR-P3 | Scaling | Throughput grows close to linearly with partitions up to the core count | Benchmark at 1, 2, 4 and 8 partitions; report the curve |
| NFR-P4 | Change-only write cost | A load with 2% changes writes ≤ 3% of rows | Rows written ÷ records read |
| NFR-P5 | Change stream lag | p99 ≤ 5 seconds from commit to the event in Kafka **[assumption]** | Commit timestamp in the event against the broker timestamp |

### 1.3 Where it is measured

All runs happen on a 16GB Windows laptop. Benchmarks run at 5 million records. Results for 50 million are a projection from the measured rate and the scaling curve, and are labelled as projections. One full 50-million-record run is attempted if disk allows **[assumption: OQ-7]**.

## 2. Resource limits (laptop)

| ID | Resource | Limit | Why |
|---|---|---|---|
| NFR-L1 | Memory, whole stack | ≤ 8GB, leaving room for the OS, IDE and browser | 16GB laptop |
| NFR-L2 | Memory, batch app | Constant heap no matter the file size (target 2GB) | Files are streamed; only chunks live in memory |
| NFR-L3 | Disk, full run | ≤ 40GB: 12.5GB input, about 20GB database, WAL and Kafka | About 49GB free today |
| NFR-L4 | Kafka disk | Short retention for benchmark topics | The change stream must not fill the disk |

## 3. Reliability and restart

| ID | Target | How it is measured |
|---|---|---|
| NFR-R1 | Zero duplicate rows after any number of kills, restarts and reruns | Kill drills at random points; count of account numbers minus count of distinct account numbers = 0 |
| NFR-R2 | Restart overhead ≤ 5 min, beyond rework of the uncommitted chunks **[assumption]** | Restart time to first new commit |
| NFR-R3 | Rework after a crash ≤ 1 chunk per partition | Records read twice, from step metrics |
| NFR-R4 | Same result whether interrupted or not | Final table hash after a killed-and-restarted run equals the hash after a clean run |
| NFR-R5 | Completes inside the window on ≥ 99% of days **[assumption]** | Reported as a projection from the window budget and drill results |

## 4. Data integrity

| ID | Target | How it is measured |
|---|---|---|
| NFR-I1 | 100% of accepted records match the database field for field | Per-record hash comparison on every run **[assumption: OQ-5]** |
| NFR-I2 | Every source record is either loaded or quarantined, never lost | Source count = accepted + quarantined |
| NFR-I3 | Decimal values are exact | No floating point anywhere from decode to database; fixed-scale `NUMERIC` only |
| NFR-I4 | A corrupt file writes nothing | Integrity faults fail before the first chunk |

## 5. Scalability

| ID | Target |
|---|---|
| NFR-S1 | Partitions are defined by record ranges, so the same job runs with local threads or remote workers without changing the business logic **[assumption: OQ-3]** |
| NFR-S2 | The design states how 100 million records per window would be reached (more partitions, more workers, database sizing), as a projection from measured numbers |

## 6. Security and privacy

| ID | Target |
|---|---|
| NFR-SEC1 | Names, addresses and payment details are masked in logs, metrics, API responses and reconciliation reports |
| NFR-SEC2 | Original bytes in quarantine are kept in a separate table with restricted access; only the masked report is generally readable |
| NFR-SEC3 | Payment details contain only the last 4 digits of a card or bank account, never a full number |
| NFR-SEC4 | Secrets come from environment variables or a secret store, never from the repository |
| NFR-SEC5 | The job control API requires authentication; operator actions are audited (FR-34) |

## 7. Observability

| ID | Target |
|---|---|
| NFR-O1 | Metrics per job and partition: read, written, unchanged, skipped, rate, chunk commit time, retries |
| NFR-O2 | A dashboard shows tonight's progress against the window budget in section 1.1 |
| NFR-O3 | Structured logs carry the job, file and partition ID on every line |
| NFR-O4 | An alert fires when the projected finish time is later than the window end |

## 8. Maintainability and testability

| ID | Target |
|---|---|
| NFR-M1 | A new layout version is added as a copybook definition and tests, without changing decoder code |
| NFR-M2 | Every COBOL field type and every injected fault type has an automated test |
| NFR-M3 | The full stack starts locally with one Docker Compose command |
| NFR-M4 | The generator is deterministic from its seed, so any failure can be reproduced |

## 9. Retention

| Data | Retention |
|---|---|
| Input and control files | 30 days **[assumption]** |
| Quarantined records with original bytes | 90 days **[assumption]** |
| Job metadata, manifest, reconciliation reports, audit | 1 year **[assumption]** |
| Benchmark change-stream topics | Hours, not days |

## 10. Disaster recovery

**Recovery point:** the last committed chunk of every partition. **Recovery time:** restart overhead ≤ 5 minutes (NFR-R2).

| Failure | Detection | Expected behaviour | Data effect |
|---|---|---|---|
| Worker JVM crash mid-job | Heartbeat stops; job status goes to `FAILED` or `UNKNOWN` | Restart resumes each partition from its checkpoint | None; replayed chunks change nothing |
| Database unavailable briefly | Connection errors | Retry with backoff (FR-20) | None |
| Database unavailable for long | Retry limit reached | Job fails cleanly; restart after recovery | None |
| Corrupt, truncated or out-of-order file | Integrity check (FR-3) | Rejected before any write | None |
| File shorter than expected but internally valid | Missing-account share above threshold (FR-17) | Job fails before marking anything missing | Changed rows are written; no account is marked missing. Reload the correct file |
| Bad-record rate above threshold | Skip count (FR-21) | Job fails; quarantine kept for review | Rows already committed stay; fix and reload |
| Wrong file loaded | Operator or reconciliation | Reload the last good snapshot (FR-22) **[assumption: OQ-4]** | Table returns to its previous state; consumers receive the corrective events |
| Kafka or Debezium down | Connector status, replication slot lag | Load continues; the connector catches up from the replication slot | Events are late, not lost. Slot size is watched so the database disk does not fill |
| Laptop disk full | Write errors | Job fails; restart after cleanup | None |
