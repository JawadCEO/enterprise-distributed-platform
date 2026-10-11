# fraud-detection-ml: Understanding and Plan

**Status:** draft for discussion. No code yet.

## Brief

| Item | Value |
|---|---|
| Domain | Real-time payment risk scoring with feature-store lookups, ML inference, and SHAP explanations |
| Stack | Python / Go, XGBoost / PyTorch, Redis (feature store), Apache Kafka, PostgreSQL, OpenTelemetry, Kubernetes |
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
- Kafka: transactions, decisions, feature updates

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, non-goals, latency budget, ADR on low-latency serving options |
| 1 | Feature key design in Redis, decision and model-registry schema, label feedback |
| 2 | Transaction and decision topics, ordering per card, late and duplicate events |
| 3 | Scoring flow, timeout and fallback, feature computation, model loading and hot swap |
| 4 | Payment-data handling, access control, decision audit |
| 5 | Latency by stage, model drift and score-distribution monitoring, cost model |
| 6 | Latency benchmark, fallback drills, offline model evaluation, skew tests |
| 7 | Deployment, shadow and canary model rollout, rollback |
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
