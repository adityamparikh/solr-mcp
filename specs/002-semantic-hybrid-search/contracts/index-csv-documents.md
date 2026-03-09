# Contract: index-csv-documents Tool (Updated for 2.0.0)

**Feature**: 002-semantic-hybrid-search
**Tool name**: `index-csv-documents`
**MCP annotation**: `@McpTool(name = "index-csv-documents", ...)`
**Changed from**: pre-2.0.0 (2 parameters) → 2.0.0 (4 parameters, 2 new optional)

**See also**: `index-json-documents.md` — identical new-parameter semantics; input format is the only difference.

**Field name note**: CSV column headers (first row) become `SolrInputDocument` field names. `textFields` must reference these header names exactly.

---

## Parameter Schema

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to index documents into |
| `csv` | `String` | Yes | — | CSV string with header row; each subsequent row is one document |
| `generateEmbeddings` | `Boolean` | No | `false` | When true, generates vector embeddings from `textFields` and stores them in each document |
| `textFields` | `List<String>` | No | `[]` | Column header names whose values are concatenated for embedding generation |

**Backward compatibility**: Both new parameters are optional with defaults that preserve pre-2.0.0 behavior.

---

## Response Schema

Same as `index-json-documents`:

```json
{
  "indexed": <int>,
  "skipped": <int>,
  "errors": ["<error message>", ...]
}
```

---

## Validation Rules

Same as `index-json-documents`. Key rule: `textFields` must reference column headers exactly as they appear in the CSV header row (after any sanitization applied by `FieldNameSanitizer`).

---

## Java Method Signature (post-2.0.0)

```java
@McpTool(name = "index-csv-documents", description = "...")
@PreAuthorize("isAuthenticated()")
@Observed
public IndexingResponse indexCsvDocuments(
    String collection,
    String csv,
    Boolean generateEmbeddings,  // NEW — nullable; default false
    List<String> textFields      // NEW — nullable; default empty list
)
```
