# fraud-detection-ml: Problem Statement

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md) and hold until those questions are decided.

## 1. Problem in one sentence

Decide, for every card payment and within 25ms, how likely it is to be fraud, using the card's behaviour up to the last second, and always return an answer that can be explained afterwards, even when parts of the system are failing.

## 2. Background

A card payment is authorised in a single synchronous round trip: merchant, acquirer, card network, issuer, and back, usually in a few hundred milliseconds. The issuer's fraud check is one step inside that round trip and gets a small, fixed slice of it. If the check is slow, the whole payment is slow; if it does not answer, the issuer must approve or decline blind.

Fraud checks built only on fixed rules ("decline if more than 5 payments in a minute") face a trade-off that cannot be tuned away:

| Cost | Cause |
|---|---|
| **Missed fraud** | Rules encode yesterday's patterns. Fraudsters adapt faster than rules are rewritten. |
| **False declines** | Tightening rules to catch more fraud also blocks genuine customers. A false decline loses the sale and often the customer. |

A model that combines many weak signals, especially the card's own recent behaviour, separates the two classes better than any hand-written rule set. That moves the trade-off curve: more fraud caught at the same false decline rate.

## 3. Why this is hard

The model is the easy part. The engineering problem is everything around it.

1. **Strict latency budget.** Fetching features, running the model, and answering must fit in 25ms at the 95th percentile and 50ms at the 99th, at 10,000 payments per second. The tail matters as much as the median, because every slow answer is a slow payment.
2. **Fresh behaviour.** The most predictive signals are behavioural: how many payments this card made in the last minute, how far it is from the last one. They are only useful if they reflect payments made a second ago, so they must be maintained by a stream, not a nightly batch.
3. **Training and serving must agree.** Features are computed one way offline for training (Python, over historical data) and another way online for serving (a stream processor, in real time). If the two differ even slightly, the model's measured accuracy does not hold in production. This "training-serving skew" is one of the most common causes of silent ML failure.
4. **Point-in-time correctness.** When training, each historical payment must see only the features that existed at that moment. Using later data leaks the future into training and overstates accuracy.
5. **Extreme class imbalance.** Roughly 1 payment in 500 to 1,000 is fraud. Plain accuracy is meaningless (a model that approves everything is 99.9% accurate), so quality must be measured with ranking and tail metrics.
6. **Delayed, noisy labels.** Whether a payment was fraud is often known only weeks later, through a chargeback. The system decides now and learns later.
7. **Always answer.** A fraud check that fails closed blocks all payments; one that fails open lets all fraud through. When the model is slow or down, a simpler rule engine must answer instead, and the decision must say it did.
8. **Explainability.** Analysts and regulators need to know why a payment was declined. Computing exact explanations on every request costs latency, so it has to happen off the critical path without losing the link to the decision.
9. **Safe model change.** A new model can be worse in ways the offline evaluation missed. It must be scored in shadow against live traffic before it affects anyone, and rolled back in seconds if it misbehaves.

## 4. Users and systems

| Actor | What they need from the system |
|---|---|
| Payment authorisation system (caller) | A decision for each payment within its deadline, every time, with the same answer if it retries |
| Fraud analysts | The decision, score, and the reasons behind it for any payment; a way to record confirmed fraud |
| Data scientists | Training data with correct point-in-time features; evaluation reports; safe promotion and rollback of models |
| Platform operators | Latency by stage, fallback rate, feature freshness, and model drift on dashboards and alerts |
| Card holders (indirect) | Genuine payments approved; fraudulent ones stopped |

## 5. Goals and success metrics

| # | Goal | Success metric |
|---|---|---|
| G1 | Score fast enough to sit inside authorisation | p95 < 25ms, p99 < 50ms at the scoring service boundary, at 10,000 payments/sec |
| G2 | Always answer | 99.999% of valid requests receive a decision within the deadline; a fallback decision counts as an answer |
| G3 | Catch fraud with few false declines | ROC-AUC > 0.96 and recall ≥ 60% at a false positive rate ≤ 0.1% on a time-based holdout **[assumption]**; PR-AUC reported alongside |
| G4 | Keep behaviour fresh | A payment is reflected in its card's online features within 1 second (p99) |
| G5 | Train and serve the same features | Parity test shows identical feature values online and offline for the same event history |
| G6 | Explain every decision | Every decision has stored reason codes at response time and SHAP attributions shortly after |
| G7 | Change models safely | Shadow scoring before promotion; rollback to the previous model in under 10 seconds without restart |

Accuracy figures depend on the dataset and are reported as achieved, whatever they turn out to be. Throughput is measured on a 16GB laptop; if one laptop cannot reach 10,000 payments/sec, the measured per-instance figure is reported and the target is shown as a projection (see [non-functional-requirements.md](non-functional-requirements.md)).

## 6. Non-goals

- A case management user interface for analysts
- Chargeback and dispute processing
- Connection to real payment networks or real authorisation messages (ISO 8583)
- Customer notifications or step-up authentication (one-time codes, 3-D Secure)
- Models that retrain continuously online
- Identity verification and onboarding checks
- Real card or customer data of any kind
- Deep-learning models; one gradient-boosted tree model is the production model **[assumption]**

## 7. Constraints

| Constraint | Effect on the design |
|---|---|
| Everything runs and is measured on a 16GB Windows laptop | Memory budget per component; small Kafka and Redis footprints; benchmark runs measured in minutes, not days |
| No real payment data | A synthetic or public dataset; cards are represented by tokens only |
| Labels arrive late | Training uses a label cut-off; evaluation accounts for labels not yet known |
| Scoring in Go, training in Python **[assumption]** | The same feature logic exists in two languages, so parity must be tested, not assumed |

## 8. Key assumptions

| Assumption | Source |
|---|---|
| Dataset: IBM TabFormer synthetic card transactions for accuracy, plus our own generator for load **[assumption]** | Open question 1 |
| Scoring service and feature pipeline in Go; training and attribution in Python **[assumption]** | Open question 2 |
| XGBoost is the only model family **[assumption]** | Open question 3 |
| Fraud rate in evaluation data is between 0.1% and 0.5% | Typical for card fraud |
| The caller's deadline for the fraud check is 100ms; answers later than that are discarded by the caller **[assumption]** | Used to define availability |
