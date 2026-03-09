# Contract: search Tool (Updated for 2.0.0)

**Feature**: 002-semantic-hybrid-search
**Tool name**: `search`
**MCP annotation**: `@McpTool(name = "search", ...)`
**Changed from**: pre-2.0.0 (7 parameters) → 2.0.0 (10 parameters, 3 new optional)

---

## Parameter Schema

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to search |
| `query` | `String` | Yes | — | Search query text |
| `filterQueries` | `List<String>` | No | `[]` | Solr filter queries (fq); applied to all modes |
| `facetFields` | `List<String>` | No | `[]` | Fields to facet on; applies to keyword leg only in hybrid mode |
| `sortClauses` | `List<String>` | No | `[]` | Solr sort expressions (keyword mode only; ignored in semantic/hybrid) |
| `start` | `Integer` | No | `0` | Result offset (keyword mode only; always 0 for semantic/hybrid) |
| `rows` | `Integer` | No | `10` | Max results for keyword mode |
| `mode` | `String` | No | smart default | Search mode: `"keyword"`, `"semantic"`, or `"hybrid"`. When omitted: `"hybrid"` if an embedding model is configured, `"keyword"` if not. |
| `topK` | `Integer` | No | `10` | Max nearest neighbors for semantic/hybrid mode; ignored in keyword mode |
| `vectorField` | `String` | No | `"vector"` | Solr field name containing document embeddings; used in semantic/hybrid mode |

**Backward compatibility**: All three new parameters (`mode`, `topK`, `vectorField`) are optional with defaults that preserve pre-2.0.0 behavior when no embedding model is configured.

---

## Response Schema

```json
{
  "numFound": <long>,
  "start": <long>,
  "maxScore": <float | null>,
  "documents": [
    { "<field>": "<value>", ... }
  ],
  "facets": {
    "<field>": { "<value>": <count>, ... }
  }
}
```

**`numFound` semantics by mode:**
- `keyword`: Total matching documents in the corpus (standard Solr behavior)
- `semantic`: Count of documents returned (≤ topK)
- `hybrid`: Count of documents returned after RRF merge (≤ topK)

---

## Validation Rules

1. If `mode` is supplied but not one of `"keyword"`, `"semantic"`, `"hybrid"`: return error listing accepted values.
2. If `mode="semantic"` or `mode="hybrid"` and no embedding model is configured: return error `"Semantic/hybrid search requires an embedding model to be configured."` Do NOT silently fall back to keyword.
3. If `mode` is omitted and no embedding model is configured: execute keyword search silently (no error).
4. If `mode="semantic"` or `mode="hybrid"` and Solr does not support KNN (e.g., Solr 8.11): catch `RemoteSolrException` and return error `"Semantic search requires Solr 9.0 or later."`.
5. `topK` must be a positive integer; default 10.
6. `vectorField` must be non-empty when supplied; default `"vector"`.

---

## Behavioral Contract by Mode

### keyword mode
- Executes BM25 full-text search using existing `SearchService` logic (no change)
- `filterQueries`, `facetFields`, `sortClauses`, `start`, `rows` all apply
- `topK` and `vectorField` are ignored

### semantic mode
- Embeds `query` text using the configured `EmbeddingModel`
- Issues `{!knn f=<vectorField> topK=<topK>}<embedding>` query via POST
- `filterQueries` applied as Solr `fq`
- `facetFields`, `sortClauses`, `start`, `rows` are ignored
- `numFound` = actual count of documents returned

### hybrid mode
- Issues keyword search AND semantic search independently (two Solr round-trips)
- Merges results via Reciprocal Rank Fusion (k=60)
- Returns top `topK` documents by RRF score
- `filterQueries` applied to both legs
- `facetFields` applied to keyword leg only; facet counts included in response
- `sortClauses`, `start` ignored; `rows` used for keyword leg only (internally)
- `numFound` = actual count of documents after merge

---

## Java Method Signature (post-2.0.0)

```java
@McpTool(name = "search", description = "...")
@PreAuthorize("isAuthenticated()")
@Observed
public SearchResponse search(
    String collection,
    String query,
    List<String> filterQueries,
    List<String> facetFields,
    List<String> sortClauses,
    Integer start,
    Integer rows,
    String mode,         // NEW — nullable; null triggers smart default
    Integer topK,        // NEW — nullable; default 10
    String vectorField   // NEW — nullable; default "vector"
)
```

---

## Authentication

Unchanged from pre-2.0.0. `@PreAuthorize("isAuthenticated()")` applies to all modes. New parameters do not alter security behavior (FR-014).
