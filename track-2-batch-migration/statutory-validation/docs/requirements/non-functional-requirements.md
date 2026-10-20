# statutory-validation: Non-Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** are working assumptions until confirmed. Items marked **[OQ-n]** follow the recommended answer in [open-questions.md](open-questions.md).

Every target below names how it is measured. A target with no measurement is not a requirement.

## 1. Reference workload [OQ-4]

All performance targets refer to this workload unless stated otherwise.

| Parameter | Value |
|---|---|
| Rules per evaluation | 40 |
| Line items per filing | 50 |
| Request size | about 7 KB of JSON |
| Outcome mix | 80% pass, 20% fail with 1 to 5 violations **[assumption]** |
| Rule set versions in use | 3 jurisdictions, 2 versions each (current and prior year) **[assumption]** |

## 2. Service level objectives

| Area | SLI (what we measure) | Target | Window |
|---|---|---|---|
| Latency | Server-side time from request received to response sent, including the audit commit | p95 < 20ms, p99 < 80ms | Any 10-minute run at target load |
| Throughput | Successful validations per second sustained | 5,000/sec with latency targets still met | 10-minute run |
| Availability | Non-5xx responses / all well-formed requests | 99.95% (about 21.6 minutes of failure a month) | Calendar month |
| Audit completeness | Successful responses with no committed audit entry | 0 | Always (checked by reconciliation) |
| Audit lag | Time from outbox commit to inclusion in the hash chain | p99 < 5s **[assumption]** | Continuous |
| Determinism | Replays whose result hash differs from the original | 0 | Every replay and every CI run |
| Rule propagation | Time from publish or rollback to every instance using it | < 30s **[assumption]** | Every publish |

Note on availability: because the service fails closed when the audit write fails **[OQ-3]**, PostgreSQL availability caps service availability. Database failover time counts against the 99.95%.

## 3. Latency budget (estimates to be measured in Phase 5)

| Step | Budget at p95 |
|---|---|
| Parse and schema-check the request | 1 ms |
| Look up rule set version (in memory) | < 0.1 ms |
| Separate personal data, canonicalise, hash | 1 ms |
| Evaluate 40 rules over 50 line items (stateless Drools session) | 5 ms |
| Commit outbox row (personal data, snapshot, result, audit entry) | 5 ms |
| Serialise response | 0.5 ms |
| Headroom | 7.4 ms |
| **Total** | **20 ms** |

## 4. Where it runs and how numbers are reported

| Environment | Purpose | Footprint |
|---|---|---|
| 16GB laptop (Docker or kind) | Every build, test and load run | About 2 GB: 2 validation instances, 1 audit-service, 1 rule-management, PostgreSQL, Redis |
| Production-shaped cluster | Not built. Used only for projections | Sized from laptop measurements |

Each result is reported as a **measured** laptop figure plus a **projected** cluster figure with the scaling method stated (for example per-core throughput times cores, checked against the database write limit). The 5,000/sec target is expected to be a projection.

## 5. Scalability

| Component | How it scales | Known limit |
|---|---|---|
| validation-service | Horizontally; stateless | CPU for evaluation, then database connections for the outbox write |
| audit-service | One active writer per chain, by design | A single chain must absorb peak rate with batched inserts. If it cannot, split into one chain per jurisdiction (future) |
| PostgreSQL | Vertically; time-partitioned tables | Write throughput of the outbox at peak |
| Memory per validation instance | Compiled rule set versions held in memory | Current and prior year kept loaded; older versions loaded on demand for replay only |

## 6. Security and compliance

| Requirement | Target |
|---|---|
| Caller authentication | Every API call authenticated (OAuth2 client credentials) |
| Authorisation | Roles: submitter, rule author, approver, auditor, operator, privacy officer. Each endpoint allows only the roles that need it |
| Audit immutability | The application role has insert-only rights on audit tables; updates and deletes are rejected by the database |
| Separation of duties | Author cannot approve their own rule change (checked in GitHub and again at registration) |
| Encryption | TLS on every connection; personal data store encrypted at rest with its own key |
| Personal data in logs, metrics, traces, audit | None |
| Retention | Audit records 7 years **[assumption]**; see [data.md](data.md) for the rest |

## 7. Observability

- **Metrics:** latency histogram per rule set version, validations by outcome, violations per rule (rule hit rate), outbox depth, audit lag, propagation time, loaded versions per instance, chain verification result.
- **Traces:** one trace per request through parse, evaluate, outbox commit.
- **Logs:** structured, keyed by evaluation ID, never containing personal data.
- **Alerts:** audit lag above target, verification failure, outbox growing, instance on a stale rule version after the propagation window.

## 8. Maintainability

- A rule change touches only the rules repository, never application code.
- Every rule set version is reproducible from Git: same commit, same content hash.
- Golden cases live next to the rules they test.

## 9. Disaster recovery

| Failure | Expected behaviour | Recovery point | Recovery time |
|---|---|---|---|
| One validation instance dies | Others serve traffic; Kubernetes replaces it | No loss (stateless) | Seconds |
| PostgreSQL primary fails | Validation returns `AUDIT_UNAVAILABLE` (fail closed) until failover to the synchronous standby completes | 0 committed audit entries lost | < 60s **[assumption]** |
| Redis unavailable | Evaluation is unaffected (rule sets are in memory). Publish notifications fall back to instances polling the database | No loss | Propagation slows to the polling interval |
| audit-service down | Validation continues; outbox rows accumulate and are chained on recovery | No loss | Audit lag grows until recovery |
| Bad rule set published | Retire it; selection falls back to the previous version | Not applicable | < 30s |
| Audit data altered or damaged | Verification reports the first broken record; restore from backup and verify against the external anchors | Last valid anchor | Hours |
| Region lost | Out of scope **[assumption]** | | |

On the laptop, the high-availability rows (standby failover) are described and reasoned about, not run.
