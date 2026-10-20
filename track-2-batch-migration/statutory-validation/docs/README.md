# statutory-validation

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data the system handles, volumes, retention, sensitivity](requirements/data.md) |
| [Decisions still needed](requirements/open-questions.md) |

## Brief

| Item | Value |
|---|---|
| Domain | Rules engine that validates statutory and regulatory data against dynamic legal rule sets, with immutable audit trails and automated CI/CD validation |
| Stack | Java, Spring Boot, Drools, PostgreSQL, Redis, GitHub Actions, Kubernetes |
| Throughput target | 5,000 complex validations/sec |
| Latency target | p95 < 20ms, p99 < 80ms |
| Availability target | 99.95% |
| Compliance | Immutable validation logs (SOX, GDPR, regulatory audit) |

## My understanding

Legal and regulatory rules change often, and they differ by jurisdiction and by date. Hard-coding them means a release for every change. Here the rules are data: versioned, tested, and published separately from the application. A caller submits a record, the engine evaluates it against the rule set in force for that jurisdiction and date, and returns a pass or a list of violations. The same input against the same rule version must always give the same answer. Every evaluation is recorded in a log that cannot be altered, so an auditor can later see exactly which rule version produced which result. No rule reaches production until an automated pipeline has run it against a set of expected-outcome cases.

## What this project demonstrates

- Rule versioning with effective dates and a publish lifecycle
- Deterministic, reproducible evaluation
- Low-latency rule execution with pre-compiled rule sets
- A tamper-evident audit log
- Rules treated as tested, reviewed artifacts in CI/CD

## Preliminary shape (for discussion)

**Services: 3, plus a CI pipeline**

| Component | Responsibility |
|---|---|
| `rule-management` | Author, version, approve, and publish rule sets |
| `validation-service` | Stateless evaluation using compiled rule sets held in memory |
| `audit-service` | Append-only, hash-chained record of every evaluation; verification and query |
| Rule CI pipeline | Compiles rules and runs golden test cases before a rule set can be published |

**Data stores**

- PostgreSQL: rule sets, rule versions, approvals, validation results, audit log (append-only, partitioned by time)
- Redis: active rule-version pointers, optional result cache keyed by input hash and rule version

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, answers to the open questions, ADR on the rule engine, determinism definition |
| 1 | Rule set and engine spike: write the 40 synthetic rules and golden cases, prove Drools evaluation is deterministic, measure evaluation time on the laptop |
| 2 | Data layer: schema and key queries for rule versions and effective dating, outbox, audit chain, filing snapshots, personal data; partitioning and retention |
| 3 | Validation hot path: rule set selection, evaluation, outbox write in the request, fail closed, idempotency, hot reload of compiled rule sets |
| 4 | Audit integrity and data protection: chain builder, external anchors, verification, replay, erasure, insert-only database rights, encryption, roles |
| 5 | Rule CI and publishing: GitHub Actions pipeline, separation of duties, rule-management, propagation to every instance, rollback of a bad rule set |
| 6 | Testing and performance: tamper tests, reload and rollback drills, load benchmark against the latency budget, laptop measurements and projections |
| 7 | Deployment and observability: Kubernetes on the laptop, rule-set rollout independent of code rollout, dashboards and alerts |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

Roughly 2GB. The lightest project in the set.

## Risks and open questions

1. **Immutable logs conflict with GDPR erasure.** A log that can never change cannot honour a deletion request if it contains personal data. Recommendation: keep personal data out of the audit log and store only hashes and references, so the log stays immutable and the personal data stays erasable.
2. **Domain of the rules.** Which kind of statutory data (tax filings, corporate disclosures, payroll)? We need a concrete, synthetic rule set of perhaps 30 to 50 rules to make the engine real.
3. **Drools or an alternative.** Drools is powerful but heavy and can be hard to keep deterministic when rules interact. This is the Phase 0 ADR.
4. **Audit write path.** Synchronous writes guarantee no evaluation goes unrecorded but add latency against a 20ms target. Asynchronous writes are faster but need a guarantee against loss. Is Kafka justified here, or is a database outbox enough?
5. **How rules are authored.** Rule files in Git reviewed by pull request, or an API and UI? Recommendation: Git, which fits the CI/CD requirement directly.
6. **What "complex" means.** The 5,000/sec target depends on rules per evaluation and record size. We should fix a reference workload.
