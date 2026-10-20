# spring-batch-legacy: Problem Statement

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md) and stand until those questions are decided.

## 1. Context

A mainframe system of record holds 50 million customer accounts **[assumption: domain, OQ-1]**. Every night it exports a full snapshot of those accounts **[assumption: snapshot, OQ-2]** as one fixed-width file. The file is encoded in EBCDIC, and its numbers use COBOL storage formats (zoned decimal and packed decimal, COMP-3). A legacy batch job decodes the file and loads it into the systems that billing, customer service and reporting depend on.

That legacy job is the bottleneck between the mainframe and everything downstream of it.

## 2. Problem

**Every night, 50 million fixed-width EBCDIC records must be converted into correct, queryable relational data, and the changes must be published to downstream systems. Today this takes most of the overnight window, cannot resume after a failure, and cannot prove that the result is correct.**

The problem has five parts. Each one is observable and has a cost.

| # | Symptom | Root cause | Cost |
|---|---|---|---|
| P1 | The job uses most of the window | Records are processed one at a time on a single thread | A single failure means the data is late the next morning |
| P2 | A failure means starting over | No checkpoints; the write position is not recorded | Hours of rework, and rows written twice after a partial run |
| P3 | No one can show the load was correct | No reconciliation between the file and the database | Silent data loss or corruption is discovered by customers, not by the system |
| P4 | Bad records stop the whole run, or slip through | No validation layer and no quarantine | Either the whole load is late, or wrong data goes live |
| P5 | Downstream systems see changes a day late | Data is only available as a nightly file | Every consumer builds its own file parser and polling schedule |

On top of this, layout knowledge is concentrated in a few people. The rules for reading a packed-decimal balance live in COBOL copybooks, not in code that the rest of the organisation can test.

## 3. Proposed solution in one paragraph

A Spring Batch platform reads the snapshot file from object storage in parallel byte-range partitions. It decodes every field exactly according to a versioned copybook, validates it, and writes only the records that actually changed into PostgreSQL, keyed on account number. Every chunk commit is a checkpoint, so a failed job resumes where it stopped and a replayed chunk changes nothing. Records that fail validation are quarantined with their original bytes. After the load, a reconciliation step compares a hash of every source record with the matching database row and publishes a pass or fail report. Committed changes stream to Kafka through Debezium change data capture, so downstream systems receive only real changes, within seconds of commit.

## 4. Goals and success metrics

Each goal has one metric that decides whether it is met.

| ID | Goal | Metric | Target |
|---|---|---|---|
| G1 | Finish inside the window | Wall-clock time from file arrival to reconciliation complete | ≤ 2 hours for 50 million records |
| G2 | Leave room for one failure | Sustained design throughput | ≥ 10,000 records/sec **[assumption: OQ-6]** |
| G3 | Resume without rework or duplicates | Duplicate rows after a forced kill and restart, and time lost beyond the last chunk | 0 duplicates; ≤ 5 minutes lost |
| G4 | Prove correctness | Accepted source records whose database row matches field for field | 100%, checked on every run **[assumption: OQ-5]** |
| G5 | Contain bad data | Bad records quarantined instead of loaded, and the job fails above a threshold | 100% quarantined; threshold configurable |
| G6 | Publish only real change | Change events emitted per unchanged record | 0; events equal the changed record count |
| G7 | Make the layout knowledge executable | Copybook-driven decoder covered by tests for every field type and fault | Every field type and injected fault has a test |

## 5. Non-goals

| Non-goal | Reason |
|---|---|
| Rewriting COBOL business logic | Only field mapping and validation move; calculations stay on the mainframe |
| Connecting directly to a mainframe | Files arrive in object storage; transfer is out of scope |
| Real-time ingestion of single records | The source is a nightly batch export |
| Reporting, analytics or a data warehouse | Consumers do this from the change stream or the database |
| Writing back to the mainframe | Data flows one way only |
| Real legacy layouts or real customer data | Everything is synthetic, generated from a copybook we design |
| A multi-region active-active setup | One region; recovery is restart and reload |

## 6. Users and systems

| Actor | What they need | How they interact |
|---|---|---|
| Mainframe export (simulated by `legacy-file-generator`) | A place to drop files | Writes a data file and a control file to object storage |
| Batch operator | To know whether tonight's load is on time, and to act if not | Job control API: launch, status, stop, restart, history |
| Data owner or auditor | Evidence that the load is complete and correct | Reconciliation report and quarantined-record report |
| Downstream system (billing, CRM, search) | Changes, not whole files | Consumes change events from Kafka |
| Developer | Confidence that a decoder change breaks nothing | Copybook-driven test suite and generated fault files |

## 7. Constraints

| Constraint | Effect on the design |
|---|---|
| Runs and is benchmarked on a 16GB Windows laptop | Memory stays constant no matter the file size; benchmarks run at 5 million records and are projected to 50 million **[assumption: OQ-7]** |
| Laptop disk (about 49GB free today) | A full 50-million-record run needs about 12.5GB of input plus 15 to 20GB of database, so it is attempted once, not routinely |
| Spring Batch, PostgreSQL, Kafka with Debezium, MinIO in place of S3 | Fixed stack for this project |
| Synthetic data only | The generator also writes the expected values, which gives reconciliation an independent check |

## 8. Key risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| Decoder bug that is applied consistently | A wrong decoder can pass a reconciliation that uses the same decoder | Compare against expected values written by the generator, not only against our own decoding |
| A truncated file that still passes its own checks | A snapshot missing 10% of accounts would mark them all closed | Fail the job if the share of missing accounts is above a threshold |
| The first full load floods the change stream | 50 million insert events would fill Kafka and laptop disk | The initial load is excluded from change capture **[assumption]** |
| Write throughput limited by PostgreSQL | 10,000 records/sec is mostly write-bound | Changed-record-only writes, set-based upserts, bulk-load tuning in phase 1 |
