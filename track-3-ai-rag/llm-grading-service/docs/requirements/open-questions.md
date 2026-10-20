# llm-grading-service: Open Questions

Decisions needed before the data layer design. Until each is confirmed, the requirements use the working assumption below, tagged **[A1]** to **[A5]**.

| # | Question | Working assumption | Status |
|---|---|---|---|
| A1 | **Model backend.** Local model, hosted API, or both? | Both behind one interface. A 1.5B to 3B model at 4-bit on the laptop's RTX 3050 (4GB video memory) for development and load tests; a hosted API for the quality evaluation. The 2.5s target applies to the hosted backend; local latency is measured and reported. | Assumed |
| A2 | **Meaning of "500 concurrent jobs".** Queued, or being graded at the same moment? | 500 accepted and in flight (queued plus grading) with none lost. The number grading at once is limited by model capacity. | Assumed |
| A3 | **Caching.** Exact match only for grades? | Yes. Key: submission content hash, rubric version, prompt version, model version. Vector search retrieves examples and never reuses a grade. | Assumed |
| A4 | **Dataset.** Which public human-scored essay set? | ELLIPSE, for its six per-criterion human scores; fallback is the Kaggle Automated Essay Scoring 2.0 set. Licence still to be checked. | Assumed, licence unchecked |
| A5 | **Confidence.** Grade twice, or trust the model's own confidence? | Neither by default. Flag `LOW` when the score disagrees with the retrieved examples' scores, when output needed repair, or on an unclear level boundary. Re-grade a 10% sample only to measure consistency. | Assumed |
