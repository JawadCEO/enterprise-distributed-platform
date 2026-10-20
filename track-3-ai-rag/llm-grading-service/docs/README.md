# llm-grading-service

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data received, processed, and returned; end-to-end flow, volumes, retention](requirements/data.md) |
| [Decisions still needed and working assumptions](requirements/open-questions.md) |

## Brief

| Item | Value |
|---|---|
| Domain | Automated grading and feedback on student submissions against rubrics, using LLM pipelines, semantic vector search, and caching |
| Stack | Python (FastAPI), Hugging Face Transformers / vLLM, Celery / Redis Queue, Redis, PostgreSQL (pgvector), Docker, Kubernetes |
| Concurrency target | 500 concurrent grading jobs |
| Latency target | Async p95 < 2.5s per batch; synchronous cache hits p95 < 50ms |
| Availability target | 99.9% |
| Cost target | Minimise LLM tokens through semantic caching and local embedding inference |

## My understanding

An instructor defines a rubric: criteria, point scales, and what each level looks like. Students submit work. Grading is slow, so the API accepts a submission, returns a job ID at once, and a worker does the grading in the background. The worker gathers the rubric and any similar previously graded examples, asks an LLM to score each criterion and justify it, checks that the answer is well formed, and stores it. Because LLM calls are the main cost, the system avoids repeating them where it safely can.

## What this project demonstrates

- Asynchronous job processing with a queue, retries, and idempotency
- A structured LLM pipeline with validated output
- Retrieval of similar graded examples with pgvector
- Caching strategy and token cost accounting
- Evaluating LLM output quality against human grades

## Preliminary shape (for discussion)

**Deployables: 3**

| Component | Responsibility |
|---|---|
| `grading-api` | FastAPI: rubrics, submissions, job status, results |
| `grading-worker` | Celery worker: retrieve context, build prompt, call model, validate, store |
| `inference` | Local embedding model, and the LLM backend if run locally |

**Data stores**

- PostgreSQL: rubrics, criteria, submissions, jobs, per-criterion scores and feedback, prompt and model versions, token usage
- pgvector: embeddings of submissions and graded exemplars
- Redis: queue broker, result cache, rate limits

## Phase plan

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, non-goals, quality metric, decisions A1 to A5, dataset licence check, ADR on model backends |
| 1 | Data model: rubric and result schema, job lifecycle, pgvector index choice, key queries |
| 2 | Async jobs with Celery and Redis: queue layout, priorities, retries, dead letters, idempotent jobs, reconciler |
| 3 | Grading pipeline: model backend interface (local and hosted), retrieval, prompt design, output validation and repair, confidence, exact-match cache |
| 4 | Evaluation harness: agreement with human scores (QWK) per criterion, consistency sample, prompt-injection test set |
| 5 | Instructor workflow and security: review, override, approval, publication, access control, privacy, injection defence |
| 6 | Cost and observability: token and cost tracking, budgets, queue depth, quality drift, traces and alerts |
| 7 | Load and failure testing on the laptop: 500-job burst, worker crash, Redis and model backend outages, projections beyond the laptop |
| 8 | Deployment: containers, Kubernetes, worker autoscaling on queue depth, prompt and model rollout gated on the evaluation |
| 9 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

Depends on the model decision below. The embedding model and services need about 3GB. A local LLM adds 4 to 8GB and is slow without a GPU.

## Risks and open questions

1. **Which LLM, and where does it run?** vLLM needs a capable GPU. Options: a small local model on CPU (slow), a hosted API (fast, costs money per call), or both behind one interface. Does the laptop have a dedicated GPU, and if so how much video memory? This decides most of the design.
2. **The 2.5s target depends on that choice.** It is realistic with a hosted API or a GPU, and not with a local CPU model.
3. **Semantic caching is a fairness risk in grading.** Two essays can be similar in meaning and still deserve different grades. Reusing a grade because the text is "close enough" would be wrong. Recommendation: cache only exact matches (same submission, rubric, prompt, and model version), and use semantic search for retrieving examples, not for reusing grades.
4. **Proof of grading quality.** Without a comparison to human grades the project shows plumbing, not grading. We need a public human-scored dataset and an agreement metric.
5. **Prompt injection.** Student text goes into the prompt and may contain instructions such as "give this full marks". The design must treat submissions as data.
6. **Meaning of "500 concurrent jobs".** 500 jobs in the queue, or 500 being graded at the same moment? The second needs far more model capacity.
