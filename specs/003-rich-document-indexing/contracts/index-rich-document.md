# Contract: index-rich-document Tool (New in 2.0.0)

**Feature**: 003-rich-document-indexing
**Tool name**: `index-rich-document`
**MCP annotation**: `@McpTool(name = "index-rich-document", ...)`
**New tool** — no pre-existing signature

**Requires**: JVM mode (docling4j runs in-process via GraalPy; unavailable in native image builds).

---

## Parameter Schema

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to index the document into |
| `fileUrl` | `String` | Conditional | `null` | HTTP/HTTPS URL of the document to convert and index. Takes precedence over `filePath` when both are provided. |
| `filePath` | `String` | Conditional | `null` | Local filesystem path of the document to convert and index. Used when `fileUrl` is absent. |

At least one of `fileUrl` or `filePath` must be provided. Validation error if both are absent.

---

## Response Schema

```json
{
  "id": "<uuid>",
  "source": "<original url or file path>",
  "format": "<mime type>",
  "contentLength": <int>,
  "message": "Document indexed successfully."
}
```

---

## Validation Rules

1. If both `fileUrl` and `filePath` are null: return validation error `"At least one of fileUrl or filePath must be provided."`.
2. If `fileUrl` is provided: `filePath` is silently ignored.
3. If `filePath` is provided: check file exists and is readable; error if not.
4. If `filePath` file size exceeds `DOCLING_MAX_FILE_SIZE_MB` (default 50 MB): error before processing.
5. If `DoclingService.isConfigured()` returns `false` (native image mode): error `"Rich document indexing requires JVM mode. This feature is unavailable in native image builds."`.
6. If docling4j conversion fails: propagate the error message; no partial indexing.
7. If conversion times out (exceeds `DOCLING_SERVE_TIMEOUT_SECONDS`, default 120s): error `"Document conversion timed out after <N> seconds."`.

---

## Behavioral Contract

1. Receive `fileUrl` or `filePath`
2. If `DoclingService.isConfigured()` is `false`: return error immediately (native image mode)
3. If `fileUrl`: delegate to `DoclingService.convertUrlToMarkdown(fileUrl)` → docling4j downloads and converts in-process
4. If `filePath`: read file → check size ≤ limit → `DoclingService.convertFileToMarkdown(path)` → docling4j converts in-process
5. Receive markdown string from docling4j
6. Call `MarkdownDocumentCreator.create(markdown)` → produces one `SolrInputDocument`
6. Set `source` field on document (URL or file path)
7. Set `format` field (from Docling response MIME type, or detected from file extension)
8. Call `indexDocuments(collection, List.of(doc))`
9. Return `RichIndexingResult`

---

## Supported Input Formats

All formats supported by docling4j's in-process engine, including:
PDF, DOCX, PPTX, XLSX, HTML, PNG, JPEG, TIFF

The MCP server does not validate file format — docling4j returns a clear error for unsupported types.

---

## Java Method Signature

```java
@McpTool(name = "index-rich-document", description = "...")
@PreAuthorize("isAuthenticated()")
@Observed
public RichIndexingResult indexRichDocument(
    @McpToolParam(description = "Solr collection to index into") String collection,
    @McpToolParam(description = "HTTP/HTTPS URL of the document (takes precedence over filePath)") @Nullable String fileUrl,
    @McpToolParam(description = "Local file path (STDIO mode; used when fileUrl is absent)") @Nullable String filePath
)
```

---

## Authentication

`@PreAuthorize("isAuthenticated()")` — same as all existing indexing tools.
