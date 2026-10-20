# Enterprise Distributed Platform

A portfolio of 8 distributed-systems projects in one repository, organised into 4 tracks.

**Status: planning. No implementation has started.** Each project's `docs/README.md` holds its overview and 9-phase plan, and `docs/requirements/` holds its problem statement, functional and non-functional requirements, data description, and open questions. Code is written only after that project's design has been discussed and approved.

## Tracks and projects

| Track | Project | One-line summary | Core stack | Plan |
|---|---|---|---|---|
| 1. Streaming | `iot-telemetry-pipeline` | High-throughput IoT event ingestion replacing legacy polling | Java, Spring Boot, Kafka, PostgreSQL, Redis | [plan](track-1-streaming/iot-telemetry-pipeline/docs/README.md) |
| 1. Streaming | `pulselink-platform` | Real-time patient vitals and emergency alert routing | Java, WebFlux, Kafka, TimescaleDB, Redis | [plan](track-1-streaming/pulselink-platform/docs/README.md) |
| 1. Streaming | `fraud-detection-ml` | Real-time transaction risk scoring with explanations | Python/Go, XGBoost, Redis, Kafka, PostgreSQL | [plan](track-1-streaming/fraud-detection-ml/docs/README.md) |
| 2. Batch and migration | `spring-batch-legacy` | COBOL flat-file batch replaced by Spring Batch into PostgreSQL | Java, Spring Batch, PostgreSQL, Debezium | [plan](track-2-batch-migration/spring-batch-legacy/docs/README.md) |
| 2. Batch and migration | `statutory-validation` | Deterministic legal-rules validation with immutable audit | Java, Spring Boot, Drools, PostgreSQL, Redis | [plan](track-2-batch-migration/statutory-validation/docs/README.md) |
| 3. AI and RAG | `llm-grading-service` | Automated rubric-based grading with LLM pipelines | Python, FastAPI, Celery, pgvector, Redis | [plan](track-3-ai-rag/llm-grading-service/docs/README.md) |
| 3. AI and RAG | `document-ai-extractor` | PDF to structured entities with bounding-box provenance | Python, PyTorch, Kafka, MinIO, vector DB | [plan](track-3-ai-rag/document-ai-extractor/docs/README.md) |
| 4. Infrastructure | `eks-gitops-platform` | AWS EKS provisioned by Terraform, delivered by ArgoCD | Terraform, EKS, ArgoCD, Helm, Gatekeeper | [plan](track-4-infrastructure/eks-gitops-platform/docs/README.md) |

Shared modules: `track-1-streaming/common-resilience`, `track-2-batch-migration/common-database`, `track-3-ai-rag/common-fastapi`. Their contents are decided when the first project in each track reaches its service-layer design, so that they hold code two projects really share.

## How each project is worked

Every project follows the same 9 phases. Each phase is planned and discussed before anything in it is built.

| Phase | Topic |
|---|---|
| 0 | Problem definition, requirements, non-goals, NFRs, ADRs |
| 1 | Data layer: access patterns, schema, queries |
| 2 | Messaging layer: topics or queues, partitioning, retries, dead letters |
| 3 | Service layer: service boundaries, HLD, LLD, concurrency, resilience |
| 4 | Security and compliance |
| 5 | Observability and cost model |
| 6 | Verification: tests, chaos drills, load benchmarks |
| 7 | Release, deployment, autoscaling, rollback |
| 8 | ADR roll-up, runbook, portfolio write-up |

Not every phase applies to every project in the same form. Each plan document says how the phases are adapted.

## Ground rules

1. Design first. No code before the phase design is agreed.
2. One project at a time, working locally before it is pushed.
3. Everything must run on a 16GB laptop. Each design carries a memory budget.
4. Targets in the plans are design targets. Results are reported as **measured** (on the laptop) or **projected** (from a measured scaling curve), never mixed.
5. All data is synthetic or public. No proprietary code, schemas, or data from any employer.

## Workflow for each project

Projects are done one at a time. Within a project the steps are:

1. Requirements, non-goals, and architecture decisions
2. High-level design: services, communication, data flow
3. Low-level design: interfaces, API contracts, patterns, error handling
4. Database schema and the key queries
5. Review and agreement on the design
6. Implementation
7. Testing: unit, integration, failure drills, load benchmark
8. Write-up: README, architecture diagram, decisions, measured results

The first project sets the conventions (repository layout, document format, testing approach, shared modules) that the others follow.

## Proving targets on limited hardware

Several targets are larger than one laptop can run. Each is backed by evidence gathered at small scale.

| Target | Evidence |
|---|---|
| High throughput | Cap each service's CPU and memory, measure 1, 2, and 4 instances, and derive a scaling curve and a capacity calculation for the full target |
| Very high availability | Failure drills under load: kill a service, broker, or database and record recovery time and failed requests |
| Multi-region active-active | Two local clusters acting as regions, with replication and added network delay, followed by a failover drill |
| RPO and RTO | Measured directly with the same kill drills |
| Latency | Measured locally and reported at the load actually sustained |

Every result is labelled as measured or projected.

## Resolved target conflicts

| Project | Conflict | Resolution |
|---|---|---|
| `iot-telemetry-pipeline` | Zero data loss against a 50ms latency target | Synchronous replication within a region, asynchronous across regions. Devices keep each event until it is acknowledged and resend after a failover; consumers discard duplicates |
| `statutory-validation` | Immutable audit log against the GDPR right to erasure | The audit log holds only hashes and references. Personal data lives in a separate, erasable table |
| `fraud-detection-ml` | SHAP explanations against a 25ms scoring budget | The score is returned synchronously; attributions are computed afterwards |
| `llm-grading-service` | Semantic caching against grading fairness | Grades are cached on exact matches only. Semantic search is used to retrieve examples, not to reuse grades |
| `eks-gitops-platform` | Private cluster endpoint against CI access | CI runs Terraform only. ArgoCD pulls changes from inside the cluster |

## Running the infrastructure project

The GitOps layer (ArgoCD, add-ons, tenancy, policies, monitoring) is built and tested on a local Kubernetes cluster at no cost. The AWS-specific parts (network, IAM roles for service accounts, private endpoints, DNS, certificates) are then proven on AWS in short sessions, with the environment destroyed after each one.

Estimated AWS cost while running is about $0.25 to $0.30 per hour for the control plane, NAT gateway, two small nodes, and a load balancer, plus a domain. A billing alarm is set before the first session. These figures are approximate and should be checked against current AWS pricing.

## Build order

1. `iot-telemetry-pipeline` (establishes the Kafka, retry, and dead-letter patterns the rest of track 1 reuses)
2. `pulselink-platform`
3. `statutory-validation`
4. `spring-batch-legacy`
5. `fraud-detection-ml`
6. `llm-grading-service`
7. `document-ai-extractor`
8. `eks-gitops-platform` (last, so it can deploy the other projects as its tenant workloads)
