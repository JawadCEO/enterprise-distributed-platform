# spring-batch-legacy: Understanding and Plan

**Status:** draft for discussion. No code yet.

## Brief

| Item | Value |
|---|---|
| Domain | Replace legacy COBOL EBCDIC flat-file batch processing with distributed Spring Batch pipelines into PostgreSQL, with CDC streaming |
| Stack | Java, Spring Batch, Spring Boot, PostgreSQL, Kafka (Debezium CDC), Docker, AWS S3 / EFS |
| Volume target | 50 million records per daily window, within 2 hours |
| Speed target | > 7,000 records/sec with chunk-based parallel processing |
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
| 0 | Requirements, non-goals, ADR on parallel batch strategy |
| 1 | Target schema, staging design, natural keys for idempotent upsert, bulk-load tuning |
| 2 | Adapted: Kafka is used only for CDC output. Topic per table, ordering, schema evolution |
| 3 | Job and step design, partitioner, chunk size, skip and retry policy, restart semantics |
| 4 | File integrity checks, access to sensitive fields, job audit |
| 5 | Throughput and step metrics, job dashboards, cost model |
| 6 | Parity tests, kill-and-restart drills, throughput benchmark |
| 7 | Scheduled job deployment, worker scaling, rollback of a bad load |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

Roughly 4GB of memory. Disk is the real limit: 50 million records at around 200 bytes each is about 10GB for the input file alone, plus the database.

## Risks and open questions

1. **Data domain.** What do the records represent (billing, customer accounts, usage)? We need to invent a realistic copybook. It must be synthetic, not a real legacy layout.
2. **Full-volume run locally.** Run all 50 million on the laptop if disk allows, or run 5 to 10 million and project? Recommendation: benchmark at 5 million, then attempt one full run.
3. **Parallel strategy.** Multi-threaded step, local partitioning, or remote partitioning across workers. Recommendation: local partitioning by file range first, with remote partitioning designed and documented.
4. **Idempotency mechanism.** Upsert on a natural key, or a ledger of file and record position, or both.
5. **Parity proof.** Row counts and per-column checksums, or a full field-by-field comparison? The second is stronger and slower.
6. **S3 / EFS.** Local runs use MinIO. Real AWS storage would only be exercised if this is deployed through the EKS project.
