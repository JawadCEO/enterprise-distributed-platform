# statutory-validation: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** are working assumptions until confirmed. Items marked **[OQ-n]** follow the recommended answer in [open-questions.md](open-questions.md).

Priority: **Must** is required for the project to meet its goals. **Should** is planned but can slip without breaking a goal.

## Definitions

| Term | Meaning |
|---|---|
| Filing | One quarterly payroll return for one employer and one jurisdiction, with its line items **[OQ-1]** |
| Rule set | All rules for one jurisdiction and filing type, plus the reference data they use (rates, caps), valid for one effective date range |
| Rule set version | An immutable, numbered release of a rule set, identified as `{jurisdiction}/{filingType}/v{n}` and by the hash of its contents |
| Evaluation | One run of one filing against one rule set version |
| As-of date | The date the evaluation treats as "today". Set once when the request is received, recorded, and reused on replay |
| Selection date | The first day of the filing's period. It decides which rule set version applies **[assumption]** |

## A. Validation

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-1 | Must | Validate one filing through a synchronous API and return pass or fail with every violation | Response lists every violated rule with rule ID, severity, message and field path. A filing with no ERROR violations is a pass. WARNING violations are returned but do not fail the filing |
| FR-2 | Should | Validate up to 500 filings in one batch request **[assumption]** | Each filing gets its own result and its own audit record. One invalid filing does not fail the others |
| FR-3 | Must | Select the rule set version by jurisdiction, filing type and selection date | Among published versions whose effective range contains the selection date, the highest version number wins. If none matches, the response is `NO_RULE_SET` and nothing is evaluated |
| FR-7 | Must | Evaluation is deterministic | The same canonical input, rule set version and as-of date always give a byte-identical result body (excluding evaluation ID and timestamps), with violations ordered by rule ID then field path |
| FR-8 | Should | Dry run against a draft rule set | Same response shape as FR-1, flagged as a dry run. Writes no validation result and no audit record. Allowed only for rule authors and CI |
| FR-15 | Must | Reject structurally invalid filings before evaluation | Missing or wrongly typed fields return a schema error listing every problem. No rule set is evaluated and no audit record is written |
| FR-16 | Must | Refuse to answer when the evaluation cannot be recorded **[OQ-3]** | If the audit entry cannot be committed, the caller gets a retryable `AUDIT_UNAVAILABLE` error and no pass or fail |
| FR-17 | Must | Idempotent submission | A repeated request with the same idempotency key and same body returns the original result without a second evaluation. Same key with a different body is rejected |

## B. Rule lifecycle

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-4 | Must | Version every rule set with effective-from and effective-to dates | A version's contents never change after publication. Effective ranges of published versions for the same jurisdiction and filing type may overlap only when one supersedes the other by version number |
| FR-5 | Must | Lifecycle of draft, in review, approved, published, retired **[OQ-5]** | Draft is a branch, in review is an open pull request, approved is a pull request with a qualifying approval, published is merged and registered, retired is withdrawn from selection. Every transition is recorded with who and when |
| FR-6 | Must | Separation of duties | The author of a change cannot approve it. Enforced by branch protection in GitHub and checked again when the rule set is registered, using the pull request's author and approver |
| FR-12 | Must | Every rule change runs expected-outcome test cases in CI; publishing is blocked on any failure | CI compiles the rule set, runs every golden case, runs each case twice with input fields in a different order to check determinism, and fails the pull request on any mismatch |
| FR-13 | Must | Load a newly published rule set into running instances without downtime | Instances compile and warm the new version, then switch atomically. In-flight evaluations finish on the version they started with. No request fails during the switch |
| FR-18 | Must | Roll back a bad rule set without a code release | Retiring a version removes it from selection, so FR-3 falls back to the previous version. Takes effect on every instance within the propagation target |
| FR-19 | Should | Report which rule set versions each instance has loaded | An operator can confirm a publish or rollback has reached every instance |

## C. Audit and replay

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-9 | Must | Write an immutable audit record for every evaluation | The record holds evaluation ID, input hash, rule set version and content hash, as-of date, result hash, caller, time. It is committed before the response is sent |
| FR-10 | Must | Chain audit records by hash and provide a verification check | Each record holds the hash of the previous one. Verification over any range reports the first break. Modifying, deleting, inserting or reordering any record is detected |
| FR-20 | Must | Anchor the chain outside the database | The current chain head hash is written to write-once storage on a schedule **[assumption: hourly]**. Verification also checks the chain against these anchors |
| FR-11 | Must | Replay a past evaluation | Re-evaluates the stored filing snapshot against the rule set version and as-of date recorded at the time, and reports whether the new result hash matches. A later correction to the rules never changes a replay. After the snapshot's retention ends, replay accepts an original filing supplied by the auditor, checked against the recorded input hash **[OQ-6]** |
| FR-21 | Should | Query the audit trail | Find evaluations by filing ID, employer ID, rule set version or time range |

## D. Personal data

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-14 | Must | Keep personal data in a separate erasable store | Employee names and national IDs are replaced by references before anything is persisted. The audit log and validation results contain references and hashes only |
| FR-22 | Must | Erase a data subject's personal data on request | Values are deleted from the personal data store. The audit chain still verifies. Replay of affected filings reports "input no longer available" instead of a result |
