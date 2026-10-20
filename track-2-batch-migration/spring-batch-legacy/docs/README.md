# spring-batch-legacy

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data received, processed and returned, record layouts, end-to-end flow, volumes, sensitivity](requirements/data.md) |
| [Decisions still needed](requirements/open-questions.md) |

## Brief

| Item | Value |
|---|---|
| Domain | Replace legacy COBOL EBCDIC flat-file batch processing with distributed Spring Batch pipelines into PostgreSQL, with CDC streaming |
| Stack | Java, Spring Batch, Spring Boot, PostgreSQL, Kafka (Debezium CDC), Docker, AWS S3 / EFS |
| Volume target | 50 million records per daily window, within 2 hours |
| Speed target | > 7,000 records/sec with chunk-based parallel processing (design target 10,000/sec, see open question 6) |
| Fault tolerance | Restart from the failure point with no duplicate inserts |
| Integrity target | 100% field parity between the legacy export and PostgreSQL |

## My understanding

A mainframe produces large fixed-width files each day. They are encoded in EBCDIC and use COBOL number formats such as packed decimals, so they cannot be read as ordinary text. The new system reads these files, decodes each field according to the record layout, validates and transforms the records, and loads them into PostgreSQL in parallel. If the job dies part-way, it must resume where it stopped without loading anything twice. Afterwards a reconciliation step proves that what landed in the database matches the file, field for field. Changes in PostgreSQL are then streamed out through Debezium for downstream consumers.

The targets agree with each other: 50 million records in 2 hours is about 6,950 records/sec.

## What this project demonstrates

- Decoding EBCDIC and COBOL field types correctly
- Parallel batch processing with partitioning
- Restartability and idempotent loading
- Skip, retry, and reject handling for bad records
- Reconciliation as proof of correctness
- Change data capture from PostgreSQL

## Preliminary shape (for discussion)

**Deployables: 2, plus connectors and a generator**

| Component | Responsibility |
|---|---|
| `batch-migration-app` | Spring Batch jobs: read and decode, validate, transform, load, reconcile |
| `job-control-api` | Launch, monitor, stop, and restart jobs (may live in the same app) |
| Debezium connector | Streams PostgreSQL changes to Kafka |
| `cdc-consumer` (sample) | Shows a downstream consumer of the change stream |
| `legacy-file-generator` | Test tool that writes EBCDIC files from a copybook, with injected bad records |

**Data stores**

- PostgreSQL: Spring Batch metadata, staging tables, target tables, reject table, file manifest, reconciliation results
- Object storage: input files (MinIO locally in place of S3)
- Kafka: change events only

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, open questions decided, ADRs on parallel strategy and change detection |
| 1 | Copybook model, EBCDIC and COMP-3 codec, test data generator with faults and expected values |
| 2 | Target schema, manifest and quarantine tables, hash-based upsert, bulk-load tuning |
| 3 | Job and step design: intake and integrity check, partitioner, chunk size, skip and retry, restart, missing accounts |
| 4 | Reconciliation: counts, control totals, per-record hash comparison, field-level diff, report |
| 5 | Change data capture: Debezium, topic per table, keying and ordering, first-load exclusion, sample consumer |
| 6 | Job control API, authentication, masking of personal fields, audit |
| 7 | Metrics, dashboard against the window budget, late-finish alert |
| 8 | Benchmark at 5 million, scaling curve, kill-and-restart and rollback drills, one full 50 million run, projections and cost model |
| 9 | Scheduled deployment, remote partitioning design, ADRs, runbook, results write-up |

## Laptop fit (16GB)

Roughly 8GB of memory for the whole stack. Disk is the real limit: 50 million records at 250 bytes each is 12.5GB for the input file alone, plus 15 to 20GB for the database.

## Risks and open questions

The decisions still needed are listed, with their working assumptions, in [open-questions.md](requirements/open-questions.md). One more risk is tracked here:

- **S3 / EFS.** Local runs use MinIO. Real AWS storage would only be exercised if this is deployed through the EKS project.
