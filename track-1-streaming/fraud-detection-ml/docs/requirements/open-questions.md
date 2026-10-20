# fraud-detection-ml: Open Questions

Decisions needed before the data layer design. Each has a working assumption, used in the requirements documents and marked **[assumption]** there, until it is decided.

| # | Question | Working assumption | Status |
|---|---|---|---|
| 1 | **Dataset.** One source, or a generator plus a public dataset? The accuracy claim depends on this. | IBM TabFormer synthetic card transactions (24M records, Apache-2.0, has card and merchant identifiers) for the accuracy claim; our own simulator for load and failure tests. Accuracy reported as "measured on TabFormer". | Open |
| 2 | **Python or Go for the scoring service.** | Go for `scoring-service` and `feature-pipeline`; Python for `training` and `decision-recorder`. The Go service evaluates XGBoost's JSON model file directly (no native bindings), with a parity test against Python. Final runtime choice recorded in a Phase 0 ADR. | Open |
| 3 | **XGBoost or PyTorch.** | XGBoost only; PyTorch removed from the stack. XGBoost's built-in tree SHAP gives exact attributions without a separate library. | Open |
| 4 | **Recall paired with the false positive rate.** | Recall ≥ 60% at FPR ≤ 0.1%, with ROC-AUC > 0.96 and PR-AUC reported. Recall at FPR 0.1% is the primary metric for promotion. | Open |
| 5 | **Track placement.** | Stay in track 1: it is a streaming system at its core. Resilience patterns are reimplemented in Go rather than shared with the Java projects. | Open |

## Smaller assumptions raised while writing the requirements

| Assumption | Where |
|---|---|
| Caller deadline 100ms, used to define availability | NFR-3 |
| Inference budget 15ms | NFR-4 |
| Feature freshness p99 under 1 second | NFR-5 |
| Explanations stored within 5 seconds | NFR-7 |
| Idempotency window 24 hours | FR-10 |
| Events accepted up to 5 minutes late | FR-20 |
| Synchronous reason codes from cheap feature checks; SHAP only asynchronously | FR-7 |
| Decision retention 2 years | data.md |
