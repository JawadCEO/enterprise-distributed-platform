# document-ai-extractor: Non-Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md) or need confirmation.

Each target is written as a service level indicator (what is measured, and where) and an objective (the threshold). "Scaled" means the deployed cluster; "laptop" means the 16GB development machine, where targets are measured per stage and projected **[assumption]**.

## 1. Service level objectives

| Area | Indicator (how measured) | Objective (scaled) | Laptop expectation |
|---|---|---|---|
| Throughput | Pages completed per second, sustained over 1 hour, all stages | ≥ 5.6 pages/s (1,000 documents of 20 pages an hour) | Measured per stage and reported; well below target because OCR runs on CPU |
| Ingestion latency | Time from `202 Accepted` to the document being searchable, 20-page document, idle pipeline | p95 < 45 s | Measured; reported with page mix (text vs OCR) |
| Search latency | Server time for `POST /search` at the API boundary, top 20 results, with filters | p95 < 120 ms, p99 < 300 ms **[assumption]** | Measured on the full laptop index |
| Upload latency | Time for `POST /documents` to return, 5 MB file | p95 < 1 s **[assumption]** | Measured |
| Availability, search | Share of successful search requests per 30 days | 99.9% **[assumption]** | Not measured; checked by drills |
| Availability, ingestion | Accepted documents later lost | Zero | Checked by stage-kill drills |
| Freshness | Share of accepted documents searchable within 5 minutes under target load | ≥ 99% **[assumption]** | Reported |

## 2. Latency budget for a 20-page document (p95, scaled)

45 s for 20 pages is 2.25 s a page if pages run one after another, so pages must run in parallel and text pages must skip OCR. The budget below assumes the worst realistic case, every page needing OCR, with 10 page workers available **[assumption]**.

| Step | Budget | Note |
|---|---|---|
| Upload, hash, validate, store | 1.5 s | Before `202 Accepted`; not counted in the 45 s |
| Queue waits across all stages | 4 s | Bounded by autoscaling on consumer lag |
| Render pages and detect text layer | 3 s | In parallel per page |
| OCR (pages without text) | 6 s | About 2 pages per worker at about 3 s a page at 300 DPI |
| Layout and section detection | 5 s | Small model, per page, in parallel |
| Entity extraction and grounding check | 6 s | Per document, after all pages finish |
| Chunk and embed (about 40 chunks) | 5 s | Batched |
| Index (vector and full-text) and publish event | 2 s | |
| **Total** | **31 s** | Leaves 14 s of headroom under 45 s |

Each stage records its own duration so the budget can be checked against measured numbers in Phase 5.

## 3. Capacity and scalability

| Requirement | Target |
|---|---|
| Horizontal scaling | Every stage scales independently by adding consumers; no stage holds state between messages |
| Scaling signal | Consumer lag per stage topic |
| Parallelism unit | One page for render, OCR, and layout; one document for extraction and indexing |
| Index size | Search objective holds up to 10 million chunks **[assumption]** |
| Back-pressure | When a downstream stage lags, uploads are still accepted and queued; nothing is dropped |

## 4. Reliability and recovery

| Requirement | Target |
|---|---|
| Delivery | At-least-once between stages; every stage is idempotent, so the effect is exactly once |
| Idempotency key | (document ID, stage, page if any, extractor version, schema version) |
| Retries | Exponential back-off, 3 attempts by default, then dead-letter |
| Poison documents | A document that crashes a worker is isolated to the dead-letter topic and cannot block its partition |
| Recovery point objective | Zero for accepted original files; up to 5 minutes for derived data, which can be regenerated **[assumption]** |
| Recovery time objective | Search back within 15 minutes; ingestion resumes from the last committed offsets **[assumption]** |

### Failure behaviour

| Failure | Expected behaviour |
|---|---|
| Worker crash mid-document | Message is redelivered; the stage reruns for that document or page only |
| A document that always crashes a stage | Dead-lettered after N attempts; other documents keep flowing |
| Vector index unavailable | Indexing stage pauses and its lag grows; keyword search keeps serving if Postgres is up |
| Postgres unavailable | Uploads refused with `503`; workers pause; no partial state written |
| Object storage unavailable | Uploads refused cleanly; nothing half-accepted |
| Kafka unavailable | Uploads stored and recorded as pending in Postgres, then published when Kafka returns (outbox pattern) |
| Bad extraction model released | Roll back the model version and reprocess documents processed by the bad version |

## 5. Correctness and quality

| Requirement | Target |
|---|---|
| Provenance coverage | 100% of stored values have page, box, and source text |
| Grounding | 100% of stored values pass the grounding check (source text exists at the box) |
| Extraction accuracy | F1 ≥ 0.9 per entity type against SPL ground truth on a held-out set of at least 500 labels **[assumption]** |
| Box accuracy | ≥ 90% of values have IoU ≥ 0.5 with a hand-marked box, on about 50 labels **[assumption]** |
| OCR quality | Character error rate reported separately for rasterised pages versus their text-layer originals |
| Search relevance | nDCG@10 reported on a small set of hand-written queries **[assumption]** |

## 6. Security

| Requirement | Target |
|---|---|
| File limits | Size ≤ 50 MB, pages ≤ 500 **[assumption]** |
| Render limits | Per-page render timeout 30 s and memory cap 1 GB; render runs in a separate process that can be killed |
| Dangerous content | Embedded JavaScript, attachments, and external links are never executed or followed |
| Encrypted PDFs | Rejected |
| Access control | API keys with roles: reader, reviewer, operator **[assumption]** |
| Transport | TLS on every external endpoint when deployed |
| Audit | Corrections, reprocessing, replays, and deletions are recorded with who and when |

## 7. Observability

| Signal | Content |
|---|---|
| Metrics | Per stage: throughput, duration histogram, error rate, consumer lag, retries, dead-letter count. Per document: pages, OCR share, entity counts. Search: latency histogram, zero-result rate |
| Traces | One trace per document across all stages, linked by document ID |
| Logs | Structured JSON with document ID, page, stage, attempt, and versions |
| Dashboards | Pipeline health, stage latency against budget, extraction quality per version |

## 8. Cost and resources

| Requirement | Target |
|---|---|
| Laptop memory | Whole stack within 10 GB, leaving room for the OS and tools; each stage has a stated memory limit |
| Laptop GPU memory | Layout and embedding models together within 3.5 GB of the RTX 3050's 4 GB; OCR stays on the CPU |
| Cost per document | Reported per stage (CPU seconds, storage bytes), and projected to cost per 1,000 documents at cloud prices |
| Storage growth | Page images kept only for OCR pages and expired after 30 days **[assumption]** |

## 9. Maintainability

| Requirement | Target |
|---|---|
| Model versions | Every model and prompt is versioned; every stored result records the versions that produced it |
| Schema evolution | Extraction schema and event schemas are versioned; consumers accept the current and previous version |
| Swappable components | OCR engine, layout model, extractor, and embedding model sit behind interfaces, so each can be replaced without changing other stages |
| Reproducibility | Accuracy and benchmark runs are scripted and record dataset version, model versions, and hardware |
