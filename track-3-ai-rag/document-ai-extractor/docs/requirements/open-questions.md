# document-ai-extractor: Open Questions

Decisions needed before the data layer design. Until each is answered, the requirements use the working assumption shown and mark it **[assumption]**.

1. **Document type.** Drug labels (recommended, because structured versions give free ground truth), company filings, or research papers? One type done well beats several done loosely.
   *Working assumption:* DailyMed drug labels, with a sample of pages rasterised so OCR is exercised.
2. **Extraction model.** OCR plus a layout model (lighter, may need fine-tuning), a vision-language model (more capable, much heavier), or a hosted model API? The laptop has an RTX 3050 Laptop GPU with 4GB of video memory, an i5-12450H (8 cores, 12 threads), and 16GB RAM (checked with `nvidia-smi`). 4GB holds a small layout model and an embedding model together, but not a useful local vision-language model.
   *Working assumption:* Tesseract on CPU for pages without text, a small layout model and the embedding model on the GPU, and extraction guided by label sections, behind an interface so a hosted model can be added later as a comparison.
3. **Vector store.** pgvector (one database, less memory) or Qdrant (stronger built-in hybrid search)? Pick one.
   *Working assumption:* pgvector, with Postgres full-text search and reciprocal rank fusion for hybrid search.
4. **Throughput claim.** Agree that the laptop will run below 1,000 documents an hour and that the figure is reported as measured plus projected.
   *Working assumption:* agreed.
