# Contract: index-json-documents Tool (Updated for 2.0.0)

**Feature**: 002-semantic-hybrid-search
**Tool name**: `index-json-documents`
**MCP annotation**: `@McpTool(name = "index-json-documents", ...)`
**Changed from**: pre-2.0.0 (2 parameters) → 2.0.0 (4 parameters, 2 new optional)

**Scope note**: `generateEmbeddings` applies to all three indexing tools. See also `index-csv-documents.md` and `index-xml-documents.md` for the same contract applied to CSV and XML formats. All three share identical new-parameter semantics; only the input format differs.

---

## Parameter Schema

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to index documents into |
| `documents` | `String` | Yes | — | JSON array of document objects to index |
| `generateEmbeddings` | `Boolean` | No | `false` | When true, generates vector embeddings from `textFields` and stores them in each document |
| `textFields` | `List<String>` | No | `[]` | Field names whose values are concatenated for embedding generation; required when `generateEmbeddings=true` |

**Backward compatibility**: Both new parameters are optional with defaults that preserve pre-2.0.0 behavior.

---

## Response Schema

```json
{
  "indexed": <int>,
  "skipped": <int>,
  "errors": ["<error message>", ...]
}
```

| Field | Description |
|-------|-------------|
| `indexed` | Count of documents successfully indexed (with or without embeddings) |
| `skipped` | Count of documents skipped due to empty/missing text fields when `generateEmbeddings=true` |
| `errors` | List of error messages for individual document failures; empty if all succeeded |

---

## Validation Rules

1. If `generateEmbeddings=true` and no embedding model is configured: return error immediately; no documents are indexed.
2. If `generateEmbeddings=true` and `textFields` is null or empty: skip embedding generation for all documents; index documents without vector field; `skipped` count equals document count.
3. If a specific document is missing all fields listed in `textFields`: skip embedding for that document; index document without vector field; increment `skipped` count.
4. If a specific document has some but not all `textFields`: generate embedding from the fields that are present; index document with embedding.
5. `generateEmbeddings=false` (or omitted): behavior identical to pre-2.0.0; `textFields` is ignored.

---

## Behavioral Contract

### Without generateEmbeddings (default)
- Parses JSON, creates `SolrInputDocument` objects, indexes in batches of 1000
- Identical to pre-2.0.0 behavior

### With generateEmbeddings=true
1. Parse JSON documents as before
2. For each document:
   a. Collect non-null values of all `textFields` present in the document
   b. If no values collected: mark document as `skipped` (no embedding); still index document
   c. Concatenate collected values with a space separator
   d. Call `EmbeddingService.embedForIndexing(...)` to get `float[]`
   e. Set the `vectorField` value (default: `"vector"`) on the `SolrInputDocument`
3. Index in batches using existing `indexDocuments(...)` logic
4. Return response with `indexed`, `skipped`, `errors` counts

---

## Java Method Signature (post-2.0.0)

```java
@McpTool(name = "index-json-documents", description = "...")
@PreAuthorize("isAuthenticated()")
@Observed
public IndexingResponse indexJsonDocuments(
    String collection,
    String documents,
    Boolean generateEmbeddings,  // NEW — nullable; default false
    List<String> textFields      // NEW — nullable; default empty list
)
```

**Note**: The existing return type may be a `String` or structured response. For 2.0.0, consider returning a structured `IndexingResponse` record that reports `indexed`, `skipped`, and `errors` counts rather than a plain string — this is required by FR-006 ("the count of successfully indexed documents is reported").

---

## Authentication

Unchanged from pre-2.0.0. `@PreAuthorize("isAuthenticated()")` applies. New parameters do not alter security behavior.
