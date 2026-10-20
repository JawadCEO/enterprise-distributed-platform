# document-ai-extractor: Functional Requirements

**Status:** draft for discussion. Items marked **[assumption]** follow the recommended answers in [open-questions.md](open-questions.md).

**Priority:** P0 is required for v1. P1 is planned for v1 if time allows. P2 is later.

Each requirement has acceptance criteria that a test or a demo can check.

## 1. Ingestion

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-1 | P0 | Accept a PDF through an upload API with optional metadata (source, external ID such as the SPL set ID, document type) | Returns `202 Accepted` with a document ID only after the file is durably stored in object storage; returns `4xx` with a reason otherwise |
| FR-2 | P0 | Bulk import every PDF under a storage bucket prefix | Each file becomes one document; a re-run of the same import creates no new documents; the import reports counts of accepted, duplicate, and rejected files |
| FR-3 | P0 | De-duplicate by SHA-256 of the file bytes | Uploading identical bytes twice returns the existing document ID and status, with no second pipeline run |
| FR-4 | P0 | Reject invalid or dangerous files before any processing stage | Rejects: not a PDF, encrypted, corrupt, over the size limit, over the page limit, or exceeding render limits (see NFR security). The rejection reason is stored and returned |
| FR-5 | P0 | Report document status | `GET /documents/{id}` returns overall status and per-stage status, attempts, last error, and timestamps |

## 2. Page understanding

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-6 | P0 | Split the document into pages and process pages in parallel | Each page is an independent unit of work; a document completes when all its pages complete |
| FR-7 | P0 | Detect per page whether a usable text layer exists | A page is "text" when its extractable text passes a quality check (minimum characters, share of valid words); otherwise "needs OCR". The decision and its score are stored |
| FR-8 | P0 | Run OCR only on pages that need it, at a fixed resolution | OCR output has word-level text, boxes, and confidence; boxes are converted to the same coordinate system as text-layer boxes |
| FR-9 | P0 | Produce layout blocks with type, box, and reading order | Block types: title, section heading, paragraph, list, table, figure, header/footer. Two-column pages read column by column |
| FR-10 | P0 | Identify label sections (boxed warning, indications and usage, dosage and administration, dosage forms and strengths, contraindications, and so on) | Each block is assigned to at most one section; section boundaries are stored with page and box |
| FR-11 | P1 | Extract tables with row and column structure | Each cell has row, column, span, text, and box; a table spanning pages is stored as one table with parts |

## 3. Entity extraction

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-12 | P0 | Extract the entities defined by the active schema version (see [data.md](data.md)) | Each entity type returns zero, one, or many values as the schema allows |
| FR-13 | P0 | Attach provenance to every value | Every value has page, one or more boxes, source text, confidence in [0, 1], extractor version, and schema version. A value without a box is rejected by the stage, not stored |
| FR-14 | P0 | Ground every value in the document text | The source text must appear in the page text at the given boxes; values that fail this check are dropped and counted |
| FR-15 | P1 | Flag low-confidence values for review | Values below a configurable threshold appear in a review queue |
| FR-16 | P0 | Version the extraction schema and the extractor | A new schema or extractor version can be applied to selected documents; old results stay readable until replaced |

## 4. Indexing and search

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-17 | P0 | Chunk text along layout and section boundaries, embed each chunk, and index it with document metadata | A chunk never spans two sections; each chunk records its pages and boxes |
| FR-18 | P0 | Hybrid search: keyword and vector results merged into one ranking | `POST /search` accepts a query and returns ranked chunks; both keyword-only and vector-only matches can appear |
| FR-19 | P0 | Filter search by metadata and extracted entities | Filters: document type, manufacturer, dosage form, route, has boxed warning, revision date range |
| FR-20 | P0 | Return provenance with every result | Each hit has document ID, page, boxes, snippet with matched terms marked, and score |
| FR-21 | P1 | Serve a page image with boxes drawn for a result or an entity | `GET /documents/{id}/pages/{n}/render?highlight=...` returns an image; the page is re-rendered from the PDF if no stored image exists |

## 5. Review

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-22 | P1 | Let a reviewer accept or correct a value | The original value is kept; each correction stores old value, new value, reviewer, time, and optional new box |
| FR-23 | P1 | Keep corrections across reprocessing | When a document is reprocessed, a corrected value whose source text still exists stays corrected; otherwise it is flagged for review |

## 6. Pipeline operations

| ID | Pri | Requirement | Acceptance criteria |
|---|---|---|---|
| FR-24 | P0 | Retry a failed stage for one document without rerunning earlier stages | The retried stage reads the previous stage's stored output; earlier stages show no new runs |
| FR-25 | P0 | Move a document that keeps failing a stage to a dead-letter topic | After N attempts (configurable, default 3) the document is dead-lettered with the error; an operator can replay it |
| FR-26 | P0 | Reprocess a document idempotently | Reprocessing replaces that document's results for the affected stages; it never duplicates entities, chunks, or index entries |
| FR-27 | P1 | Publish an event when a document becomes searchable | A `document.indexed` event carries document ID, schema version, and entity counts |
| FR-28 | P1 | Read extracted entities through an API | `GET /documents/{id}/entities` returns entities with provenance and correction state |
| FR-29 | P2 | Delete a document and all derived data | Removes file, pages, entities, chunks, and index entries; keeps an audit record of the deletion |
