# statutory-validation: Open Questions

Decisions needed before the data layer design. Until each is answered, the requirement documents use the recommended answer and mark it **[OQ-n]**.

1. **Domain.** Are quarterly employer payroll tax returns the right domain? Recommendation: yes.
2. **Rule engine.** Drools, a simpler decision-table approach, or our own rule evaluator? Drools is powerful but heavy, and harder to keep deterministic when rules interact. This is the main architecture decision. Recommendation: Drools, restricted so that rules only read the filing and emit violations, never change facts or trigger other rules, with violations sorted before they are returned.
3. **Audit write path.** Synchronous writes guarantee every evaluation is recorded but add latency against a 20ms target. Asynchronous writes through a database outbox are faster and still safe. Is Kafka justified here, or is the outbox enough? Recommendation: the outbox, without Kafka. The outbox insert is itself a short synchronous write, so when it fails the service returns no result (fail closed). The hash chain is built from the outbox afterwards by a single writer.
4. **Reference workload.** The 5,000/sec target depends on rules per evaluation and filing size. Recommendation: fix it at 40 rules and 50 line items per filing.
5. **How rules are authored.** Rule files in Git reviewed through pull requests (recommended, as it fits the CI requirement directly), or an API? With Git, pull request review becomes the approval step and rule-management shrinks to registering, publishing and retiring versions.
6. **How long filing inputs are kept for replay.** Replay needs the original filing, and storing every filing is the largest data volume (about 65 GB a day compressed at average load). Recommendation: keep snapshots for 90 days. After that, replay works only if the auditor supplies the original filing, which is checked against the recorded input hash before it is re-evaluated.
