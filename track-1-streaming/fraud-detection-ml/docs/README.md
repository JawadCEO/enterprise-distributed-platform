# fraud-detection-ml

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement: background, what makes it hard, goals and success metrics, non-goals, constraints](requirements/problem-statement.md) |
| [Functional requirements with priorities and acceptance checks](requirements/functional-requirements.md) |
| [Non-functional requirements: targets, latency budget, failure modes, laptop memory budget](requirements/non-functional-requirements.md) |
| [Data and flow: sources, API contract, end-to-end flows, features, topics, retention](requirements/data.md) |
| [Decisions still needed, with working assumptions](requirements/open-questions.md) |

## Brief

| Item | Value |
|---|---|
| Domain | Real-time payment risk scoring with feature-store lookups, ML inference, and SHAP explanations |
| Stack | Go (scoring, features) and Python (training, attributions), XGBoost, Redis (feature store), Apache Kafka, PostgreSQL, OpenTelemetry, Kubernetes. Language split and XGBoost-only are working assumptions; see open questions 2 and 3 |
| Latency target | p95 < 25ms, p99 < 50ms at 10,000 transactions/sec |
| Availability target | 99.999%, with fallback to a rule engine when inference times out |
| Accuracy target | ROC-AUC > 0.96, false positive rate < 0.1% |

## My understanding

A payment is waiting for an approve-or-decline answer, so the score must come back in a few tens of milliseconds. Within that budget the service has to fetch the customer's recent behaviour (for example, transaction count in the last hour), run a model, and return a score. Those behavioural features are kept fresh by a separate stream that updates them as transactions happen. If the model is slow or down, a simpler rule set answers instead, because failing to answer is worse than answering with less accuracy. Each decision is stored with the reasons behind it.

## What this project demonstrates

- A latency budget split across feature fetch, inference, and response
- An online feature store kept fresh by a stream
- Graceful degradation to rules under a strict timeout
- Consistency between training features and serving features
- Model versioning, shadow scoring, and explainability

## Preliminary shape (for discussion)

**Services: 3, plus an offline training pipeline**

| Component | Responsibility |
|---|---|
| `scoring-service` | Synchronous API: fetch features, run model, apply timeout and rule fallback, return score |
| `feature-pipeline` | Kafka consumer that maintains rolling aggregates in Redis |
| `decision-recorder` | Kafka consumer that stores decisions and computes SHAP attributions |
| `training` (offline) | Trains and evaluates the model, exports a versioned artifact |
| `txn-simulator` | Test tool that replays transactions at a chosen rate |

**Data stores**

- Redis: online features per card, account, and merchant
- PostgreSQL: decisions, scores, attributions, model registry, fallback rules, labels
- Kafka: scored payments (payment, features, and decision in one event) and labels; see [data.md](requirements/data.md#8-event-topics)

## Phase plan

Same 9-phase numbering as the rest of the repo, adapted to this project.

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, latency budget, ADRs: dataset choice, Go model runtime |
| 1 | Data and features: dataset profiling, feature specification, point-in-time feature builder (Python), baseline XGBoost model and time-split evaluation report; Redis key design; PostgreSQL schema and queries |
| 2 | Messaging and streaming features: scored-payment and label topics, partitioning by card, late and duplicate events, Go feature pipeline, training-serving parity test |
| 3 | Scoring service and recording: API, Go model evaluator and model parity test, rules, timeout and fallback, degraded mode, idempotency, hot swap; decision recorder with SHAP |
| 4 | Model lifecycle and security: model registry, promotion gate, shadow scoring, rollback, label feedback; authentication, roles, append-only decision audit |
| 5 | Observability: latency by stage, feature freshness, consumer lag, fallback rate, score and feature drift; projected cost per million scores |
| 6 | Verification: open-loop latency benchmark, failure drills (model, Redis, Kafka), final holdout evaluation, holdout replay through the live API |
| 7 | Release: Kubernetes deployment, canary model rollout, rollback drill |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

Roughly 4GB. XGBoost inference is light and needs no GPU. Comfortable.

## Risks and open questions

1. **Python or Go for the scoring service.** The plan lists both. Python is simplest with XGBoost but harder to hold at p99 < 50ms under load. Go is faster but needs a model runtime. This is the main Phase 0 decision.
2. **XGBoost or PyTorch.** Recommendation: XGBoost as the one production model. It suits tabular fraud data and has fast exact SHAP. Add PyTorch only if there is a reason.
3. **SHAP inside the 25ms budget.** Computing attributions on every request eats latency. Recommendation: return the score synchronously and compute SHAP asynchronously in `decision-recorder`.
4. **Dataset.** We need labelled fraud data. Options are a public dataset or generated data. The accuracy target depends entirely on this choice, so the numbers are reported as achieved, whatever they are.
5. **False positive rate needs a paired recall.** FPR < 0.1% is easy if the model flags almost nothing. The target should state the recall required at that FPR.
6. **Track placement and shared code.** This project is Python/Go while the other two in track 1 are Java, so `common-resilience` (if Java) cannot be shared with it. The patterns can be; the code cannot.
7. **Five nines is a design target**, shown through fallback and failure drills.
