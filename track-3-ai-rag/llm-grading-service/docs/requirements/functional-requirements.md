# llm-grading-service: Functional Requirements

**Status:** draft for discussion. **[A1]** to **[A5]** are working assumptions from [open-questions.md](open-questions.md); **[assumption]** marks other unconfirmed items.

Priority: **P0** is required for the first working version, **P1** is required before the results write-up, **P2** is optional.

## 1. Rubrics and examples

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-1 | Create and version rubrics. A rubric has one or more criteria; each criterion has ordered levels, each with points and a descriptor. | P0 | Editing a published rubric creates a new version; existing results keep pointing at the version they were graded with. |
| FR-2 | Attach human-graded example submissions to a rubric version, with a score per criterion. | P0 | Each example is embedded on upload and is retrievable by vector search within that rubric version only. |
| FR-3 | Validate rubrics on save. | P0 | Rejects a rubric with no criteria, duplicate level points, or a criterion with fewer than two levels. |

## 2. Submissions and jobs

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-4 | Accept one submission or a batch (up to 500 **[assumption]**) for an assignment and return a job ID per submission immediately. | P0 | The submission is durably stored before `202 Accepted` is returned; no grading happens on the request path. |
| FR-5 | Make submission idempotent. | P0 | Re-sending the same client submission ID returns the existing job ID and does not enqueue new work. |
| FR-6 | Report job status: `QUEUED`, `GRADING`, `NEEDS_REVIEW`, `AWAITING_APPROVAL`, `PUBLISHED`, `FAILED`. | P0 | Status is readable at any time by job ID and by assignment. |
| FR-7 | Notify a registered callback URL when a job reaches a terminal or review state. | P1 | Callbacks are signed, retried with backoff, and their delivery is logged. |
| FR-8 | Serve exact repeats from cache. | P0 | A submission whose content hash, rubric version, prompt version, and model version all match a stored result returns that result with no model call **[A3]**. |

## 3. Grading pipeline

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-9 | Retrieve the k most similar graded examples (default k = 2) for the submission from the same rubric version. | P0 | Retrieved examples and their similarity scores are recorded on the job. |
| FR-10 | Score every criterion on the rubric's own scale, with a justification and a verbatim evidence quote. | P0 | Every score is a valid level for its criterion; every quote is found verbatim in the submission text. |
| FR-11 | Produce short overall feedback addressed to the student. | P0 | Feedback is stored separately from criterion scores. |
| FR-12 | Validate model output against a JSON schema. On failure, retry once with a repair prompt; on a second failure, mark the job `NEEDS_REVIEW`. | P0 | No invalid output is ever stored as a result. |
| FR-13 | Treat submission text strictly as data. | P0 | Submission text is delimited and never concatenated into instructions; an injection test set shows no significant score increase (problem statement G7). |
| FR-14 | Route results through a pluggable model backend with a local and a hosted implementation, selectable per environment and with a configured fallback **[A1]**. | P0 | Switching backends needs configuration only; the result records which backend and model produced it. |

## 4. Confidence and review

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-15 | Assign confidence `HIGH` or `LOW` per job **[A5]**. A job is `LOW` if any of: the model score differs by more than one level from the similarity-weighted score of the retrieved examples; the output needed a repair; a score falls on a level boundary the model flagged as unclear. | P1 | The reason for every `LOW` flag is stored. |
| FR-16 | Send `LOW` jobs to `NEEDS_REVIEW` and `HIGH` jobs to `AWAITING_APPROVAL`. | P0 | Nothing skips the instructor (FR-18). |
| FR-17 | Let an instructor override any criterion score with a reason. | P0 | The original score, new score, reason, and instructor are kept; overrides feed quality measurement. |
| FR-18 | Publish grades to students only after instructor approval, per job or in bulk per assignment. | P0 | A student-facing read of an unapproved result returns nothing. |
| FR-19 | Re-grade a random sample (default 10%) a second time to measure consistency **[A5]**. | P1 | Consistency rate is reported per rubric version. |

## 5. Cost, audit, and administration

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-20 | Record backend, model, prompt version, input tokens, output tokens, and cost for every model call. | P0 | Totals are queryable per job, assignment, course, and day. |
| FR-21 | Enforce a token budget per course **[assumption]**. | P1 | When the budget is reached, new jobs stay `QUEUED` and administrators are alerted; accepted work is never dropped. |
| FR-22 | Role-based access: instructors see only their courses; students see only their own published results; administrators manage models and budgets. | P1 | Access checks are enforced in the API, not in the client. |
| FR-23 | Keep an audit trail of rubric changes, overrides, approvals, and publications. | P1 | Each entry records who, what, and when. |

## 6. Evaluation

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---|---|
| FR-24 | Run an offline evaluation: grade a held-out set from the chosen dataset **[A4]** and report QWK per criterion against human scores. | P1 | The report is reproducible from a fixed dataset split, prompt version, and model version. |
| FR-25 | Run the injection test set and report score shift against clean versions. | P1 | Reported alongside FR-24. |
