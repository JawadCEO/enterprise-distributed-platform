# document-ai-extractor

**Status:** draft for discussion. No code yet.

## Documents

| Document |
|---|
| [Problem statement, goals, users, non-goals](requirements/problem-statement.md) |
| [Functional requirements](requirements/functional-requirements.md) |
| [Non-functional requirements and disaster recovery](requirements/non-functional-requirements.md) |
| [Data and data flow: what we receive, how each stage processes it, what we give back](requirements/data.md) |
| [Decisions still needed](requirements/open-questions.md) |

## Brief

| Item | Value |
|---|---|
| Domain | Asynchronous pipeline that ingests unstructured PDFs (scientific and regulatory public documents), runs OCR and vision-language extraction, and indexes structured entities for hybrid search |
| Stack | Python, PyTorch / Hugging Face, Tesseract / LayoutLM, Apache Kafka, Qdrant / PostgreSQL (pgvector), MinIO / S3, Docker, Kubernetes |
| Throughput target | 1,000 multi-page documents/hour |
| Ingestion latency target | p95 < 45s for a 20-page document |
| Search target | Hybrid semantic query p95 < 120ms |
| Governance | Every extracted field traceable to its source bounding box |

## My understanding

A user uploads a PDF. The pipeline works through it in stages: store the file, turn pages into text and layout (using OCR only where the page has no text layer), extract structured entities, split and embed the text, and index everything for search. Each stage is a separate consumer so a slow stage can be scaled alone and a failed stage can be retried without redoing the others. The distinguishing requirement is provenance: every extracted value records the page and the rectangle it came from, so a reader can check it against the original.

## What this project demonstrates

- A multi-stage asynchronous pipeline with per-stage retry and scaling
- Layout-aware extraction rather than plain text scraping
- Provenance from extracted field back to source region
- Hybrid search combining keyword and vector results
- Object storage, a relational store, and a vector index working together

## Preliminary shape (for discussion)

**Deployables: 4**

| Component | Responsibility |
|---|---|
| `ingest-api` | Upload, de-duplicate by content hash, track status |
| `layout-worker` | Page rendering, text-layer detection, OCR, layout blocks with coordinates |
| `extraction-worker` | Entity extraction with bounding boxes, chunking, embedding, indexing |
| `search-api` | Hybrid query, filters, results with highlighted source regions |

**Data stores**

- MinIO: original PDFs, page images, OCR output
- PostgreSQL: documents, pages, pipeline stage status, extracted entities with page and box, extraction schema versions
- Vector index: text chunks and embeddings (pgvector, working assumption; see open questions)
- Kafka: one topic per pipeline stage, plus retry and dead-letter topics

## Phase plan

The project keeps the shared 9 phases. Phases 1, 3, 4, 6 and 7 are adapted to this project, as follows.

| Phase | Focus for this project |
|---|---|
| 0 | Requirements, non-goals, extraction schema v1, ADRs on layout-aware extraction and the vector store |
| 1 | Data layer and evaluation set: document, page, block, entity, and chunk schema; provenance and coordinate model; key queries including hybrid search; DailyMed terms check, SPL ground truth, rasterised OCR pages, and about 50 labels with hand-marked boxes |
| 2 | Messaging: stage topics, passing references instead of files, page fan-out and the join back to one document, outbox on upload, retries, dead letters, poison documents |
| 3 | Stages: service boundaries, page-level parallelism, CPU pool for OCR, layout and embedding models sharing the 4GB GPU, extractor interface, hybrid ranking |
| 4 | Security and licences: upload limits, sandboxed rendering of malicious PDFs, API key roles, source terms and model licences (LayoutLMv3 is non-commercial) |
| 5 | Observability and cost: per-stage latency against the 45s budget, CPU and GPU use, extraction quality per model version, cost per 1,000 documents |
| 6 | Verification: F1 and box accuracy on the evaluation set, OCR error rate, search relevance, stage-kill drills, throughput measured per stage and projected |
| 7 | Release: Docker Compose for daily work, a local Kubernetes run with lag-based autoscaling (stages run in groups to fit 16GB), model version rollout with reprocessing and re-indexing |
| 8 | ADRs, runbook, results write-up |

## Laptop fit (16GB)

The heaviest project in the set, roughly 8 to 10GB with models loaded. Stages may have to run a few at a time rather than all at once.

## Risks and open questions

1. **Throughput on a laptop.** 1,000 twenty-page documents an hour is about 5.5 pages/sec. OCR on CPU typically manages around one page per second per core, so the laptop figure will be well below target. Skipping OCR for pages that already have a text layer recovers much of it. Reported as measured plus projected.
2. **Extraction model.** Tesseract plus LayoutLM (lighter, needs fine-tuning data), or a vision-language model (more capable, far heavier), or a hosted model API. Same GPU question as the grading service.
3. **One vector store, not two.** The plan lists Qdrant and pgvector. Recommendation: pick one in Phase 1. pgvector keeps everything in one database and saves memory; Qdrant has stronger built-in hybrid search.
4. **What entities do we extract?** "Structured entities" needs a concrete schema for one document type (for example, drug name, indication, dosage, and sponsor from regulatory filings). One type done well beats several done loosely.
5. **Document source.** Only public documents whose terms allow this use. We should pick the source and check its terms before building.
6. **Accuracy measurement.** We need a small hand-labelled set to report extraction accuracy; otherwise the provenance claim has nothing behind it.
