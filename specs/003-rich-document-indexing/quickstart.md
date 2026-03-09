# Quickstart: Rich Document Indexing

**Feature**: 003-rich-document-indexing
**Audience**: Developers implementing or testing this feature

---

## Prerequisites

1. **Solr** running locally (any version 8.11–10)
2. **JVM mode** for `index-rich-document` (docling4j runs the Python conversion engine in-process via GraalPy — no external Docker container or sidecar required):
   ```bash
   java -jar solr-mcp.jar   # index-rich-document works out of the box
   ```
3. `index-markdown` requires **no external services** beyond Solr and works in all modes (JVM and native image)
4. **Note**: `index-rich-document` is **unavailable** in GraalVM native image builds — the ML dependencies (PyTorch, ONNX, OCR) cannot be statically compiled. See feature 001 (native image) for details.

---

## Step 1: Index a PDF via URL

Call `index-rich-document` with a publicly accessible PDF URL:

```json
{
  "tool": "index-rich-document",
  "arguments": {
    "collection": "my_collection",
    "fileUrl": "https://arxiv.org/pdf/2408.09869"
  }
}
```

Expected response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "source": "https://arxiv.org/pdf/2408.09869",
  "format": "application/pdf",
  "contentLength": 42381,
  "message": "Document indexed successfully."
}
```

---

## Step 2: Index a Local File (STDIO Mode)

```json
{
  "tool": "index-rich-document",
  "arguments": {
    "collection": "my_collection",
    "filePath": "/Users/me/documents/report.docx"
  }
}
```

---

## Step 3: Search the Indexed Document

```json
{
  "tool": "search",
  "arguments": {
    "collection": "my_collection",
    "query": "neural network benchmark results"
  }
}
```

The document indexed in Step 1 will appear if it contains those keywords.

---

## Step 4: Index a Markdown Document with Frontmatter

```json
{
  "tool": "index-markdown",
  "arguments": {
    "collection": "my_collection",
    "markdown": "---\ntitle: Q1 Report\nauthor: Jane Smith\ncategory: Finance\n---\n\n## Summary\n\nRevenue up 12%.",
    "id": "q1-report-2026"
  }
}
```

Expected response:
```json
{
  "id": "q1-report-2026",
  "frontmatterFields": ["title", "author", "category"],
  "skippedFields": [],
  "message": "Markdown document indexed successfully."
}
```

---

## Step 5: Search on a Frontmatter Field

```json
{
  "tool": "search",
  "arguments": {
    "collection": "my_collection",
    "query": "Revenue",
    "filterQueries": ["category:Finance"]
  }
}
```

---

## Error Cases

| Situation | Response |
|-----------|----------|
| `index-rich-document` with no `fileUrl` or `filePath` | `"At least one of fileUrl or filePath must be provided."` |
| `index-rich-document` called in native image mode | `"Rich document indexing requires JVM mode. This feature is unavailable in native image builds."` |
| `index-rich-document` with file > 50 MB | `"File exceeds maximum size of 50 MB: <path>"` |
| `index-rich-document` conversion timeout | `"Docling Serve timed out after 120 seconds."` |
| `index-markdown` with reserved frontmatter key (`id`) | Indexed successfully; `"id"` appears in `skippedFields` |
| `filePath` file not found | `"File not found or not readable: <path>"` |

---

## Running Tests

```bash
# Unit tests (mocked Docling + mocked Solr)
./gradlew test --tests "IndexingServiceRichDocTest"
./gradlew test --tests "MarkdownDocumentCreatorTest"

# Integration tests (real Solr + real Docling Serve via Testcontainers)
# Requires Docker daemon running
./gradlew test --tests "IndexingServiceRichDocIntegrationTest"

# Full build
./gradlew build

# Solr version matrix
./gradlew test -Dsolr.test.image=solr:8.11-slim --tests "*IntegrationTest"
./gradlew test -Dsolr.test.image=solr:10-slim --tests "*IntegrationTest"
```

---

## Configuration Reference

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `DOCLING_SERVE_TIMEOUT_SECONDS` | `120` | Timeout for docling4j in-process conversion (GraalPy) |
| `DOCLING_MAX_FILE_SIZE_MB` | `50` | Maximum local file size for `filePath` inputs |

---

## Key Design Decisions

- **docling4j runs in-process — no external backend**: `com.ibm.docling:docling4j:0.1.1` embeds the Python docling engine via GraalPy (Oracle's embeddable Python runtime). No external Docker container, sidecar, or `DOCLING_SERVE_URL` needed. Works out of the box across all 6 deployment modes (STDIO/HTTP × source/JAR/Docker image).
- **Markdown as the canonical intermediate format**: All rich documents are converted to and stored as Markdown. This integrates naturally with full-text search and can be combined with semantic embedding (feature 002).
- **`index-markdown` has no Docling dependency**: Users with existing markdown content can index without any Docling runtime — works in JVM and native image modes.
- **MarkdownDocumentCreator is the 4th SolrDocumentCreator**: The existing strategy pattern (JSON, CSV, XML) now has a 4th implementation, fully satisfying Constitution Principle V (abstraction justified by 3+ callers).
- **Native image limitation**: GraalPy CAN run in native image mode, but docling's ML dependencies (PyTorch, ONNX, OCR — native C++ libs) cannot be statically compiled. `DoclingService.isConfigured()` returns `false` in native image; `index-rich-document` returns a clear error.
