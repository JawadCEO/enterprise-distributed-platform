# spring-batch-legacy: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md).

**Priority:** P0 is required for the system to be correct. P1 is required for the portfolio scope. P2 is designed and documented, and built only if time allows.

Each requirement says what the system must do, and the acceptance criterion says how a test proves it. The stages follow the data flow in [data.md](data.md#5-end-to-end-flow).

## 1. Intake and integrity

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-1 | P0 | Detect a new file only when both the data file and its control file are present in the inbound bucket | A data file without its control file is never picked up; once both are present, a job starts within 60 seconds |
| FR-2 | P0 | Register every file in a manifest with a unique file ID, business date and status. The same file content is never loaded twice as a new load | Re-dropping a file whose checksum is already `LOADED` is refused, unless an operator asks for a reload |
| FR-3 | P0 | Verify the file before any record is written: SHA-256 against the control file, file size equal to a whole number of records, exactly one header and one trailer, and a header business date later than the last loaded date (an operator reload, FR-22, is the only exception) | Each injected integrity fault (truncation, bad checksum, missing trailer, stale date) fails the job with zero rows written |
| FR-4 | P0 | Verify the trailer record count against the file size before loading, and the trailer balance total against the loaded and quarantined records after loading (FR-23) | A wrong trailer count fails before any write; a balance total off by 0.01 fails reconciliation, and the report names the mismatch |

## 2. Decoding

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-5 | P0 | Drive decoding from a versioned copybook definition, not from hand-written offsets. The header names the layout version | A detail record decodes correctly for every layout version present in the test set |
| FR-6 | P0 | Decode EBCDIC text (code page 037) with trailing-space trimming | Round trip: generator value → EBCDIC → decoded value is identical for all printable characters in the code page |
| FR-7 | P0 | Decode unsigned and signed zoned decimal, and packed decimal (COMP-3) with implied decimal scale, without floating point | Every COMP-3 value from −999,999,999.99 to +999,999,999.99 at the boundaries decodes exactly to a fixed-scale decimal |
| FR-8 | P0 | Route records by type: header, detail and trailer | A detail record in the header position, or a second trailer, fails the integrity check |
| FR-9 | P1 | Support repeating groups (`OCCURS`) and alternative layouts selected by a discriminator (`REDEFINES`) | Address lines decode as three entries; payment details decode as card or bank according to the payment method |

## 3. Validation and transformation

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-10 | P0 | Validate each detail record: valid digits and sign nibble, mandatory fields present, valid calendar dates, values in range, codes in their code lists | Each injected record fault is rejected with its own reason code |
| FR-11 | P0 | Transform to the target model: dates to `DATE`, amounts to `NUMERIC` with a fixed scale, trimmed text, codes mapped to reference values | A golden-file test produces exactly the expected target rows |
| FR-12 | P0 | Compute a deterministic hash of each record's canonical decoded values | The same record gives the same hash across runs, partitions and JVMs; changing any single field changes the hash |

## 4. Loading

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-13 | P0 | Load in parallel partitions, each covering a contiguous range of records **[assumption: local partitioning, OQ-3]** | Partition ranges cover every record exactly once, with no gap and no overlap |
| FR-14 | P0 | Upsert on account number. Write a row only when it is new or its record hash has changed **[assumption: snapshot plus hash, OQ-2]** | A second load of an identical file writes 0 rows and emits 0 change events |
| FR-15 | P0 | Record on every row the file ID that last wrote it and the file ID that last confirmed it unchanged **[assumption: OQ-4]** | For any account, a query returns which file last wrote it and which file last saw it |
| FR-16 | P0 | After all partitions finish, mark accounts that are absent from the snapshot as missing. Do not delete them **[assumption]** | An account removed from the file is flagged `missing_since = business date`, and one change event is emitted |
| FR-17 | P0 | Fail the job, before marking any account missing, if the missing share is above a threshold (default 1%) **[assumption]** | A file containing 95% of yesterday's accounts fails with zero accounts marked missing |

## 5. Failure handling and restart

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-18 | P0 | Commit in chunks. Checkpoint each partition's position in the same transaction as its data | After a kill, every partition's checkpoint equals the last record it committed |
| FR-19 | P0 | Restart a failed job from its checkpoints. Completed partitions are not run again | A kill at 50% followed by a restart gives the same final table hash as an uninterrupted run, with 0 duplicates |
| FR-20 | P0 | Retry transient errors (lost connection, deadlock, serialization failure) with backoff. Never retry a data error | A database restart mid-job is survived without operator action, up to the retry limit |
| FR-21 | P0 | Skip a bad record into quarantine with its reason code, field, position and original bytes. Fail the job when skips exceed a threshold (default 0.1% of records) | At a 0.05% fault rate the job succeeds with every fault quarantined; at 0.2% it fails |
| FR-22 | P1 | Roll back a bad load by reloading the last good snapshot **[assumption: OQ-4]** | After loading a wrong file and then reloading the previous one, the table hash equals the hash before the wrong load |

## 6. Reconciliation

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-23 | P0 | After each load, prove source = accepted + quarantined, and that trailer totals match the sum of accepted and quarantined records | Any mismatch fails the job, and the report shows the expected and actual figures |
| FR-24 | P0 | Recompute the record hash from every database row written or confirmed by this file and compare it with the source hash **[assumption: OQ-5]** | One field changed directly in the database by a test is reported with account number and field name |
| FR-25 | P0 | Compare field by field only for the records whose hash differs, and report each differing field | The report lists field name, source value and database value, with personal fields masked |
| FR-26 | P1 | In test runs, compare against the expected values written by the generator, independently of our decoder | A deliberately broken decoder fails reconciliation, even though its own hash check passes |
| FR-27 | P0 | Store a reconciliation report per file, with a final status of `PASSED` or `FAILED` | The report is available through the API and kept for 1 year |

## 7. Change data capture

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-28 | P0 | Publish every committed insert, update and missing flag on the target tables to Kafka through Debezium | Event count equals the changed record count in the reconciliation report |
| FR-29 | P0 | Keep per-account ordering: all events for one account go to the same partition, in commit order | A consumer replaying the topic rebuilds the exact table state |
| FR-30 | P1 | Exclude the first full load from the change stream; consumers start from a snapshot instead **[assumption]** | The first load emits no events; the next load emits only its changes |
| FR-31 | P1 | Provide a sample consumer that applies events to its own store, and so proves that they are usable | The sample consumer's store matches the source table after a load |

## 8. Job control and audit

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-32 | P0 | API to launch a load by file ID, show status and progress per partition, stop, restart and list history | Each operation works through the API alone, without database access |
| FR-33 | P1 | Show progress: records read, written, unchanged and skipped, the rate, and an estimated finish time | Values update at least every 10 seconds during a run |
| FR-34 | P1 | Record an audit entry for every operator action: who, what, when and which file | Every launch, stop, restart and reload has an audit row |
| FR-35 | P2 | Remote partitioning: the manager hands partitions to separate worker processes **[assumption: designed in OQ-3, built only if time allows]** | Killing one worker reassigns its partition; the result is identical |

## 9. Test data generator

| ID | Pri | Requirement | Acceptance criterion |
|---|---|---|---|
| FR-36 | P0 | Generate an EBCDIC snapshot of N accounts from the copybook, reproducible from a seed | The same seed and N give the same file bytes |
| FR-37 | P0 | Generate day N+1 from day N with a set change rate, new accounts and removed accounts | Measured changes equal the requested rate |
| FR-38 | P0 | Inject faults at a set rate: invalid packed digits, bad sign nibble, impossible dates, missing mandatory fields, unknown codes, truncation and wrong trailer totals | Each fault type appears in quarantine with its expected reason code |
| FR-39 | P1 | Write an expected-values file alongside the data file, for FR-26 | The expected values match the logical records used to build the file |
