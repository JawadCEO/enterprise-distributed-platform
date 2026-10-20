# fraud-detection-ml: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** need confirmation.

Priority: **Must** is required for the project to meet its goals; **Should** is planned but can slip a phase; **Could** is done only if time allows. Each requirement has an acceptance check that a test or a demo can prove. Request and event formats are in [data.md](data.md).

## Scoring (synchronous path)

| ID | Priority | Requirement | Acceptance check |
|---|---|---|---|
| FR-1 | Must | Expose a scoring API: one payment in; transaction ID, decision (`APPROVE`, `REVIEW`, `DECLINE`), score in [0, 1], reason codes, model version, and fallback and degraded flags out | Contract test against the schema in data.md |
| FR-2 | Must | Validate every request (required fields, types, currency code, amount > 0, timestamp not more than 5 minutes in the future **[assumption]**) and reject invalid ones with a client error that names the field | Invalid requests return 400 with the field name; they are not scored |
| FR-3 | Must | Fetch the online features for the card, account, and merchant (and device when present) in a single round trip to the feature store | One Redis round trip per request, visible in the trace |
| FR-4 | Must | Compute request-time features from the payment itself (amount, merchant category, channel, hour of day, amount relative to the card's average) using the same definitions as training | Covered by the parity test (FR-27) |
| FR-5 | Must | Score with the active model version loaded in memory | Decision record shows the model version that produced the score |
| FR-6 | Must | Map the score to a decision with configurable thresholds (`REVIEW` at or above one threshold, `DECLINE` at or above a higher one) | Changing thresholds changes decisions without a restart |
| FR-7 | Must | Return reason codes synchronously, derived from cheap feature checks (for example `VELOCITY_1M_HIGH`, `AMOUNT_UNUSUAL_FOR_CARD`) **[assumption]**; exact attributions follow asynchronously (FR-15) | Every non-approve decision carries at least one reason code |
| FR-8 | Must | Fall back to the rule engine when inference exceeds its time budget or no model is loaded; mark the decision `fallback = true` with the cause | Fault-injection test: a slowed model produces fallback decisions, never errors |
| FR-9 | Must | When the feature store is unavailable or slow, score with default feature values plus the rule engine and mark the decision `degraded = true` | Redis stopped during load: every request still answered |
| FR-10 | Must | Idempotency: a repeated request with the same transaction ID returns the original decision and is not scored or recorded twice, within a 24-hour window **[assumption]** | Replay test: duplicates return byte-identical decisions |
| FR-11 | Must | Publish one scored-payment event per decision (payment, features used, score, decision, model version, flags, latency by stage) | Count of events equals count of unique decisions |
| FR-12 | Must | Keep answering when the event broker is down: buffer scored-payment events locally (bounded) and send them on recovery | Kafka stopped during load: no decision lost after recovery, up to the buffer limit |

## Rule engine

| ID | Priority | Requirement | Acceptance check |
|---|---|---|---|
| FR-13 | Must | Evaluate an ordered set of rules (condition on features, resulting decision, reason code, priority) loaded from the database | Unit tests per rule; rules reload without restart |
| FR-14 | Should | Run the rule engine on every request alongside the model and record which rules fired, so rule-only performance can be compared with the model | Decision record lists fired rules |

## Explanations and decision record

| ID | Priority | Requirement | Acceptance check |
|---|---|---|---|
| FR-15 | Must | Compute exact SHAP attributions for each model-scored decision after the response, and store the top 10 contributing features with their values and contributions | Attributions sum (with the base value) to the model's raw score within 1e-6 |
| FR-16 | Must | Store every decision durably with the payment, features used, score, decision, reason codes, model version, fallback and degraded flags, and stage latencies | Reconciliation: decisions in the database equal scored-payment events |
| FR-17 | Should | Look up a decision and its attributions by transaction ID through an API for analysts | Returns the full record, including attributions once computed |

## Online features (streaming path)

| ID | Priority | Requirement | Acceptance check |
|---|---|---|---|
| FR-18 | Must | Consume scored-payment events and maintain, per card: payment count and amount sum over 1 minute, 1 hour, and 24 hours; distinct merchants over 24 hours; time since the previous payment; distance from the previous payment's location | Feature values match a reference computation over the same events |
| FR-19 | Should | Maintain per merchant: payment count over 1 hour and 24 hours; per account: payment count over 24 hours; per device: distinct cards over 24 hours (when device is present) | Same as FR-18 |
| FR-20 | Must | Use event time, not arrival time, for windows; accept events up to 5 minutes late **[assumption]** and count each event once even if delivered twice | Late and duplicate event tests |
| FR-21 | Must | Process events for one card in order (partition by card token) | Ordering test with interleaved cards |
| FR-22 | Must | Expire window data automatically so memory stays bounded | Redis memory stable over a long run |

## Labels, training, and models

| ID | Priority | Requirement | Acceptance check |
|---|---|---|---|
| FR-23 | Must | Accept labels (`FRAUD`, `GENUINE`, `CHARGEBACK`) with source and time, possibly weeks after the payment, and link them to the decision | Labels joined to decisions by transaction ID |
| FR-24 | Must | Offline training pipeline: build point-in-time features from the event history, train with a time-based split, and produce a versioned model artifact and an evaluation report (ROC-AUC, PR-AUC, recall at FPR 0.1%, calibration, feature importance) | Report generated per training run |
| FR-25 | Must | Register each model with version, training data window, feature list, metrics, and status (`CANDIDATE`, `SHADOW`, `ACTIVE`, `RETIRED`) | Registry query shows full history |
| FR-26 | Must | Promote a candidate only if it beats the active model on the same holdout set by the primary metric (recall at FPR 0.1% **[assumption]**) | Promotion refused otherwise, with the reason |
| FR-27 | Must | Training-serving parity test: compute features offline (Python) and online (Go) over the same event history and compare every value | Zero mismatches beyond floating-point tolerance |
| FR-28 | Must | Model parity test: the Go runtime and the Python library produce the same score for the same features | Max absolute difference below 1e-6 over the holdout set |
| FR-29 | Must | Shadow mode: score with a candidate model alongside the active one without affecting decisions; store both scores; report agreement and score distributions | Shadow report generated; decisions unchanged |
| FR-30 | Must | Swap the active model without restarting the service or failing in-flight requests | Swap under load: zero errors |
| FR-31 | Must | Roll back to the previous active model with one command | Rollback completes in under 10 seconds |

## Test tooling

| ID | Priority | Requirement | Acceptance check |
|---|---|---|---|
| FR-32 | Must | Payment simulator: generate payments for a configurable population of cards and merchants at a chosen rate, with injected fraud patterns (card testing, account takeover, unusual location) and delayed labels | Generates 10,000 payments/sec from one process |
| FR-33 | Should | Replay a recorded dataset (for example the holdout set) through the live API in time order | Replay produces decisions comparable with the offline evaluation |
