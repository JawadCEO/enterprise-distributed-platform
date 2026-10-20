# llm-grading-service: Non-Functional Requirements

**Status:** draft for discussion. **[A1]** to **[A5]** are working assumptions from [open-questions.md](open-questions.md); **[assumption]** marks other unconfirmed items. Figures marked *estimate* are to be replaced by measurements.

## 1. Service level objectives

Each target names the indicator it is measured with, so it can be checked from metrics rather than argued about.

| Area | Indicator (SLI) | Target (SLO) | Notes |
|---|---|---|---|
| Submission availability | Share of well-formed submit requests answered `202` | 99.9% per 30 days | About 43 minutes of error budget a month |
| Result availability | Share of status and result reads answered successfully | 99.9% per 30 days | Served from PostgreSQL; survives a Redis outage |
| Submit latency | API response time for `POST /submissions`, p95 | Under 200ms | Store and enqueue only; no model work |
| Cache-hit latency | API response time when an exact-match result exists, p95 | Under 50ms | **[A3]** |
| Grading latency, hosted backend | Worker pickup to result stored, p95 | Under 2.5s | **[A1]** |
| Grading latency, local backend | Worker pickup to result stored, p95 | Measured and reported, *estimate* about 10s | 3B model at 4-bit on the RTX 3050 **[A1]** |
| Queue wait | Submission accepted to worker pickup, p95 | Under 60s at normal load; drains within 30 minutes after a 500-job burst on the laptop | **[A2]** |
| In-flight capacity | Jobs queued or grading at once without loss | 500 | **[A2]**: in flight, not 500 simultaneous model calls |
| Durability | Accepted submissions lost | Zero | Stored in PostgreSQL before the job ID is returned |

## 2. Quality

| Area | Indicator | Target |
|---|---|---|
| Agreement with humans | QWK per criterion on the held-out test set **[A4]** | At least 0.70 with the hosted backend **[assumption]**; local backend reported, not gated |
| Consistency | Same score on re-grade, per criterion, 10% sample **[A5]** | At least 90% **[assumption]** |
| Evidence integrity | Quotes found verbatim in the submission | 100% of stored results |
| Injection resistance | Mean score shift on injected versus clean essays | Not significantly above zero |
| Review targeting | Share of instructor overrides that fall on `LOW` confidence jobs | Reported; higher is better |

## 3. Capacity model

| Figure | Value | Basis |
|---|---|---|
| Peak submissions per day | 100,000 | **[assumption]** |
| Average arrival rate at peak | About 1.2 per second | 100,000 / 86,400 |
| Deadline burst | About 12 per second | 10 times the average **[assumption]** |
| Model calls in flight at burst, hosted | About 36 | Little's law: 12/s x about 3s per job |
| Local throughput, laptop | *Estimate* 0.3 to 0.5 jobs per second | 1 GPU, small batch; to be measured |
| Tokens per submission | About 3,500 (3,000 in, 500 out) | See [data.md](data.md) |
| Tokens per day at peak | About 350 million | 100,000 x 3,500 |

The queue absorbs the gap between burst arrivals and model throughput. Throughput scales by adding workers and model capacity; the API and database are not the bottleneck at these rates.

## 4. Laptop resource budget (16GB RAM, 4GB video memory)

| Component | RAM | Video memory |
|---|---|---|
| Docker and WSL2 overhead | 2.0GB | |
| PostgreSQL with pgvector | 1.0GB | |
| Redis | 0.3GB | |
| `grading-api` | 0.3GB | |
| `grading-worker` x 2 | 1.0GB | |
| Embedding model (384 dimensions) | 0.5GB | |
| Local LLM server, 3B at 4-bit | 1.5GB | 3.0 to 3.5GB |
| Load generator and monitoring | 1.0GB | |
| **Total** | **about 7.6GB** | **about 3.5GB of 4GB** |

This leaves headroom for the operating system and an editor. Larger-scale numbers are projected from measured per-worker throughput, not run on the laptop.

## 5. Reliability and disaster recovery

| Failure | Expected behaviour | Recovery objective |
|---|---|---|
| Worker crashes mid-job | Job is acknowledged only after the result is stored, so it returns to the queue and is graded again; the result write is idempotent on job ID | No lost or duplicated results |
| Model backend slow or down | Calls time out after a set limit; the job retries with backoff, then fails over to the fallback backend if configured; otherwise it waits in the queue | Grading resumes automatically when the backend returns |
| Repeated failure on one job | After 3 attempts **[assumption]** the job moves to a dead-letter queue and is marked `FAILED` for an operator | Visible on a dashboard; can be re-queued |
| Malformed model output | One repair retry, then `NEEDS_REVIEW` | Never stored as a result |
| Redis unavailable | New submissions are refused with `503` rather than stored without a queue entry; results and status stay readable from PostgreSQL | Restart Redis; a reconciler re-enqueues stored jobs still `QUEUED` |
| PostgreSQL unavailable | API refuses writes and reads; workers pause | Restore from backup: RPO 5 minutes, RTO 1 hour **[assumption]** |
| Token budget reached | New jobs stay `QUEUED`; administrators alerted | Resumes when the budget is raised or resets |

## 6. Security and privacy

| Requirement | Detail |
|---|---|
| Pseudonymous students | The service stores only a pseudonymous student ID; names and emails stay in the learning platform |
| Nothing identifying reaches a model | Prompts contain rubric, examples, and submission text only |
| Prompt-injection defence | Submission text is delimited and labelled as data; the system prompt states it may contain instructions to ignore; output is schema-validated; evidence quotes are checked verbatim |
| Access control | Roles per FR-22; service-to-service calls authenticated |
| Encryption | TLS in transit; database volume encrypted at rest in deployed environments |
| Retention | Enforced by scheduled deletion per [data.md](data.md) |
| Secrets | Hosted model API keys come from environment or a secret store, never from code or images |

## 7. Observability

| Signal | Examples |
|---|---|
| Metrics | Queue depth per priority, jobs by status, grading latency by backend, cache hit rate, tokens and cost per course, repair rate, `LOW` confidence rate, override rate |
| Logs | Structured, keyed by job ID; no submission text in logs |
| Traces | One trace per job across API, queue, worker, retrieval, and model call |
| Alerts | Error budget burn, dead-letter growth, budget reached, QWK on the rolling override sample falling below target |

## 8. Maintainability

- Prompt and model versions are explicit identifiers stored on every result, so a change can be evaluated before rollout and compared after.
- A prompt or model change must pass the offline evaluation (FR-24) before it becomes the default.
- The model backend is behind one interface; adding a backend does not touch the pipeline.
