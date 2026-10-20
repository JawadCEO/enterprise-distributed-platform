# document-ai-extractor: Problem Statement

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md) and change if those answers change.

## 1. Context

Facts that matter in medicine and regulation are published as PDFs. A US drug label, for example, states the active ingredient and strength, the approved indications, the contraindications, and any boxed warning. Pharmacists, researchers, and safety analysts need these facts as data: to compare products, to check a claim, or to feed another system. The PDF is built for a human reader, not for a program.

## 2. Problem

**An analyst who needs a specific fact from a large collection of PDFs cannot get it as trustworthy, structured data without reading each document by hand.**

Three things make this hard:

| Difficulty | What goes wrong today |
|---|---|
| Manual extraction does not scale | A trained reader handles a few labels an hour. A collection of tens of thousands of labels, each revised several times, is out of reach. |
| Plain text extraction loses structure | Two-column pages merge line by line, tables collapse into a stream of words, and a value is separated from the heading that gives it meaning. Scanned pages yield no text at all. |
| Model output cannot be checked | A language model can return a fluent value that is not in the document. Without a pointer to where the value came from, a reviewer must re-read the whole document to verify it, which removes the time saved. |

The underlying gap is **verifiability at scale**: existing approaches are either accurate and slow (people) or fast and unverifiable (text dumps, unconstrained models).

## 3. Proposed approach

A staged, asynchronous pipeline that turns each PDF into verifiable structured data:

1. Keep the original file as the source of truth.
2. Per page, use the embedded text layer when present; run OCR only on pages without one.
3. Recover layout: blocks, reading order, tables, and section headings, all with coordinates.
4. Extract a fixed, versioned set of entities. Every value records its page, bounding box, source text, confidence, and the extractor version that produced it.
5. Chunk, embed, and index the text for hybrid keyword and semantic search.

Each stage runs as an independent consumer, so a slow stage scales alone and a failed stage retries alone.

**Hypothesis:** with layout-aware extraction and mandatory provenance, a reviewer can verify any extracted value by looking at one highlighted region instead of reading the document, while extraction accuracy stays at or above 0.9 F1 per entity type.

## 4. Target document type

**Published US drug labels from DailyMed [assumption].** Each label is available as a PDF and as Structured Product Labeling (SPL) XML. The XML gives correct values for most entities without hand labelling, which makes accuracy measurable on thousands of documents. One document type done well is the scope; other types are out of scope for v1.

## 5. Users and the job each needs done

| User | Job to be done | What success looks like |
|---|---|---|
| Analyst or researcher | Find labels matching a question ("oral antibiotics with a boxed warning") and read the exact supporting text | Relevant results in well under a second, each with the source region highlighted |
| Reviewer | Confirm or correct low-confidence values | Sees the value next to its highlighted source; a correction is one action and keeps history |
| Ingestion operator | Load a collection and keep the pipeline healthy | Sees each document's stage status; can retry a failed stage or replay dead-lettered documents |
| Downstream system | Consume extracted entities | A stable API and a "document indexed" event, with versioned schemas |

## 6. Goals and success metrics

| # | Goal | Metric | Target |
|---|---|---|---|
| G1 | Process collections, not single files | Sustained throughput | 1,000 documents/hour (about 5.6 pages/sec at 20 pages a document) |
| G2 | Make a new document usable quickly | Upload accepted to searchable, 20-page document | p95 < 45 s |
| G3 | Make every value verifiable | Share of extracted values with page and bounding box | 100% |
| G4 | Extract correctly | F1 per entity type against SPL ground truth | ≥ 0.9 **[assumption]** |
| G5 | Point to the right place | Share of values whose box overlaps the hand-marked region (IoU ≥ 0.5) | ≥ 0.9 **[assumption]** |
| G6 | Answer searches quickly | Hybrid query at the API boundary | p95 < 120 ms |
| G7 | Recover from failure cheaply | Work redone when one stage fails | Only that stage, for that document |

Throughput and latency targets describe the scaled deployment. On the 16GB development laptop they are measured per stage and the cluster figure is projected from those measurements, reported separately **[assumption]**.

## 7. Scope

**In scope (v1)**

- English-language, machine-generated or scanned PDFs of one document type (drug labels)
- Upload API and bulk import from a storage bucket
- Text-layer detection, OCR fallback, layout analysis, table structure
- Entity extraction against one versioned schema, with provenance
- Hybrid search with filters
- Reviewer corrections with history
- Per-stage status, retry, dead-letter handling, idempotent reprocessing

**Non-goals**

| Non-goal | Reason |
|---|---|
| Handwritten documents | Different recognition problem; not present in the chosen source |
| Languages other than English | Keeps OCR and models to one language |
| Document types other than drug labels in v1 | One type done well; the schema is versioned so more can follow |
| Editing or generating documents | Read-only pipeline |
| A knowledge graph across documents | Entities are per document; linking is a later project |
| Sub-second ingestion | The pipeline is asynchronous by design |
| Private or confidential documents | Public sources only; no access-controlled data |
| Medical advice or clinical decision support | The system reports what a label says, not what a patient should do |

## 8. Constraints

- All development, runs, and benchmarks happen on one Windows laptop: 16GB RAM, an i5-12450H (8 cores, 12 threads), and an RTX 3050 Laptop GPU with 4GB of video memory. The full stack with models loaded is roughly 8 to 10GB of RAM, so stages may run a few at a time. OCR runs on the CPU; the layout and embedding models share the 4GB GPU.
- Only documents whose source terms permit this use; the terms are checked before any download.
- The core path sends no document to a third-party service. A hosted model may be added later only as a comparison **[assumption]**.

## 9. Key assumptions

| ID | Assumption | If wrong |
|---|---|---|
| A1 | DailyMed labels are available as both PDF and SPL XML under terms that permit this use | Pick another source; the accuracy plan then needs hand labelling |
| A2 | Most label PDFs have a text layer; OCR is exercised by rasterising a sample of pages | If real scans are common, OCR dominates cost and latency |
| A3 | OCR plus a small layout model, guided by label section headings, reaches 0.9 F1 | Add a fine-tuned token classifier, or compare against a vision-language model |
| A4 | pgvector with Postgres full-text search meets the search target | Move the vector index to Qdrant |
| A5 | An average document has 20 pages | Throughput and storage figures scale linearly with page count |

## 10. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| OCR on CPU runs at about one page a second per core | Laptop throughput far below target | Skip OCR on text pages; measure per stage and project |
| SPL ground truth gives values but not boxes | Provenance accuracy unmeasured | Hand-mark boxes on about 50 documents |
| Layout model licence (LayoutLMv3 weights are non-commercial) | Limits reuse beyond a portfolio | State it in the README; keep the layout step behind an interface |
| Malicious or pathological PDFs | Worker crashes or resource exhaustion | Validation limits, sandboxed rendering, dead-letter after N attempts |
| Memory pressure on 16GB | Stages cannot all run at once | Per-stage memory budgets; run stages in groups |

## 11. Glossary

| Term | Meaning |
|---|---|
| Text layer | Text embedded in a PDF page that can be read without OCR |
| Bounding box | Rectangle on a page, in PDF points (1/72 inch) with the origin at the top left |
| Provenance | Page, bounding box, and source text recorded with every extracted value |
| SPL | Structured Product Labeling, the XML format in which US drug labels are published |
| Hybrid search | A query that merges keyword (full-text) and vector (semantic) results into one ranking |
| Stage | One independently deployed step of the pipeline, with its own input topic and retries |
