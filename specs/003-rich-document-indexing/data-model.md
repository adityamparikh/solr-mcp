# Data Model: Rich Document Indexing

**Feature**: 003-rich-document-indexing
**Date**: 2026-03-08

---

## Entities

### DocumentSource (Value Object — internal)

Represents the origin of a document submitted to `index-rich-document`.

| Field | Type | Description |
|-------|------|-------------|
| `fileUrl` | `String \| null` | HTTP/HTTPS URL; Docling Serve fetches directly |
| `filePath` | `String \| null` | Local filesystem path; server reads and encodes |

**Validation rules**:
- At least one of `fileUrl` or `filePath` must be non-null; validation error if both absent
- When both provided, `fileUrl` takes precedence; `filePath` is ignored

**State transition**:
```
fileUrl non-null  → send as HttpSource to Docling Serve
filePath non-null (fileUrl null) → read file → check size ≤ limit → encode base64 → send as FileSource
both null → validation error
```

---

### RichIndexingResult (Value Object — returned to caller)

The response from `index-rich-document`.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | UUID of the indexed Solr document |
| `source` | `String` | Original URL or file path |
| `format` | `String` | Detected MIME type from Docling response |
| `contentLength` | `int` | Length of the converted markdown (character count) |
| `message` | `String` | Human-readable summary (e.g., "Document indexed successfully") |

---

### MarkdownIndexingResult (Value Object — returned to caller)

The response from `index-markdown`.

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | UUID (or caller-supplied ID) of the indexed Solr document |
| `frontmatterFields` | `List<String>` | Field names extracted from YAML frontmatter and indexed |
| `skippedFields` | `List<String>` | Frontmatter keys skipped due to reserved Solr field names |
| `message` | `String` | Human-readable summary |

---

### DoclingService (Application Service)

Wraps the optionally-configured `DoclingServeApi` bean.

| Field | Type | Description |
|-------|------|-------------|
| `doclingApi` | `DoclingServeApi \| null` | Docling HTTP client; null when `DOCLING_SERVE_URL` not set |

| Method | Returns | Description |
|--------|---------|-------------|
| `isConfigured()` | `boolean` | True when Docling Serve URL is configured |
| `convertUrlToMarkdown(String url)` | `String` | Sends URL to Docling Serve; returns markdown; throws `McpException` if not configured or conversion fails |
| `convertFileToMarkdown(byte[] content, String filename)` | `String` | Encodes file as base64, sends to Docling Serve; returns markdown |

---

### MarkdownDocumentCreator (4th SolrDocumentCreator implementation)

Converts a markdown string into a single `SolrInputDocument`.

| Processing step | Description |
|----------------|-------------|
| Frontmatter detection | Check if content starts with `---\n`; extract YAML block |
| YAML parsing | Parse scalar key-value pairs from frontmatter via SnakeYAML |
| Reserved field check | Skip keys `id`, `_version_`, `_root_`; log warning |
| Field sanitization | Apply existing `FieldNameSanitizer` to frontmatter keys |
| Document assembly | `id` = caller-supplied or UUID; `content` = markdown body (frontmatter stripped); `format` = "text/markdown" |

---

### Solr Document Schema (produced by both tools)

Each indexed document has these fields:

| Solr field | Type | Source | Required |
|------------|------|--------|----------|
| `id` | `string` | UUID or caller-supplied | Yes |
| `content` | `text_general` | Full markdown text (frontmatter stripped for markdown tool) | Yes |
| `source` | `string` | Original URL, file path, or "direct" | Yes |
| `format` | `string` | MIME type (e.g., "application/pdf", "text/markdown") | Yes |
| `<frontmatter_key>` | `dynamic` | Each YAML frontmatter key (non-reserved) | No |

**Note**: These fields work with Solr's default schema (schemaless) or any schema that allows dynamic fields. No schema migration is required as long as the target collection is configured for schemaless indexing (the existing pattern in this project).

---

## Relationships

```
IndexingService
  └── uses DoclingService (optional — for index-rich-document)
  └── uses MarkdownDocumentCreator (for both tools; rich doc result passed through)
  └── uses SolrClient (existing — unchanged)
  └── uses IndexingDocumentCreator (existing — now includes MarkdownDocumentCreator)

DoclingService
  └── wraps DoclingServeApi (ai.docling:docling-serve-client, optional bean)

MarkdownDocumentCreator implements SolrDocumentCreator
  └── uses SnakeYAML (transitive dep via Spring Boot) for frontmatter
  └── uses FieldNameSanitizer (existing, unchanged)
```

---

## File Size Constraint

For `filePath` inputs:

```
max file size = DOCLING_MAX_FILE_SIZE_MB (default: 50) × 1_048_576 bytes
Check: Files.size(path) ≤ maxBytes → proceed
Otherwise: throw McpException("File exceeds maximum size of <N> MB: <path>")
```

---

## Frontmatter Parsing State Machine

```
content starts with "---\n"?
  YES → find closing "---\n" or "...\n"
        → extract YAML substring
        → parse with SnakeYAML (Map<String,Object>)
        → for each entry:
            key = FieldNameSanitizer.sanitize(key.toString())
            skip if key in RESERVED_FIELDS {"id", "_version_", "_root_"}
            skip if value is Map or List (nested — store as toString())
            add to SolrInputDocument
        → body = content after closing delimiter
  NO  → body = entire content (no frontmatter)

Set doc.addField("content", body)
```
