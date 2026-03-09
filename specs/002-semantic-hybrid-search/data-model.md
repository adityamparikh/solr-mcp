# Data Model: Semantic & Hybrid Search

**Feature**: 002-semantic-hybrid-search
**Date**: 2026-03-08

---

## Entities

### SearchMode (Enum)

Represents the retrieval strategy applied by the `search` tool.

| Value | Description |
|-------|-------------|
| `KEYWORD` | BM25 full-text search (pre-2.0.0 behavior). `numFound` = total corpus matches. |
| `SEMANTIC` | KNN vector similarity search. `numFound` = results returned (≤ topK). |
| `HYBRID` | Keyword + semantic merged via RRF. `numFound` = results returned (≤ topK). |

**Validation rules:**
- If `mode` parameter is supplied, must be one of the three values above; otherwise return validation error listing accepted values.
- If `mode` is omitted: resolve to `HYBRID` when `EmbeddingService.isConfigured()`, else `KEYWORD`.

**State transition:**
```
mode param absent
  → isConfigured()? → HYBRID
  → !isConfigured() → KEYWORD

mode param present ("keyword" | "semantic" | "hybrid")
  → "keyword"  → KEYWORD (always)
  → "semantic" → SEMANTIC (error if !isConfigured())
  → "hybrid"   → HYBRID   (error if !isConfigured())
  → other      → validation error
```

---

### EmbeddingService (Application Service)

Wraps the optionally-configured `EmbeddingModel` bean.

| Field | Type | Description |
|-------|------|-------------|
| `embeddingModel` | `EmbeddingModel \| null` | Spring AI model; null if no embedding provider is configured |

| Method | Returns | Description |
|--------|---------|-------------|
| `isConfigured()` | `boolean` | True when an embedding provider is available at startup |
| `embed(String text)` | `float[]` | Converts text to embedding vector; throws `McpException` if not configured |
| `embedForIndexing(List<String> fieldValues)` | `float[]` | Concatenates non-null field values and embeds; used by `IndexingService` |

---

### SemanticSearchRequest (Value Object — internal)

Used to pass parameters from `SearchService` to KNN query construction. Not an MCP-visible type.

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `collection` | `String` | Yes | Solr collection name |
| `queryText` | `String` | Yes | Raw query text; will be embedded |
| `vectorField` | `String` | Yes | Solr field name storing embeddings (default: `"vector"`) |
| `topK` | `int` | Yes | Max results to return (default: 10) |
| `filterQueries` | `List<String>` | No | Solr fq parameters; applied to KNN search |
| `queryEmbedding` | `float[]` | Yes | Pre-computed embedding of `queryText` |

---

### HybridSearchResult (Value Object — internal)

Intermediate container used during RRF merge; not MCP-visible.

| Field | Type | Description |
|-------|------|-------------|
| `document` | `SolrDocument` | The Solr document |
| `keywordRank` | `Integer \| null` | 1-based rank in keyword result list; null if not present |
| `semanticRank` | `Integer \| null` | 1-based rank in semantic result list; null if not present |
| `rrfScore` | `double` | `1/(60 + keywordRank) + 1/(60 + semanticRank)`; absent rank contributes 0 |

---

### SearchResponse (Existing — updated semantics)

Existing Java record; no structural changes. `numFound` semantics differ by mode.

| Field | Type | Description |
|-------|------|-------------|
| `numFound` | `long` | Keyword: total corpus matches. Semantic/Hybrid: count of results returned (≤ topK). |
| `start` | `long` | Offset; always 0 for semantic/hybrid |
| `maxScore` | `Float` | Max BM25 score for keyword; RRF score for hybrid; null for semantic |
| `documents` | `List<Map<String,Object>>` | Result documents |
| `facets` | `Map<String,Map<String,Long>>` | Facet counts; populated only for keyword and hybrid (keyword leg) |

---

### IndexingEmbeddingRequest (Value Object — internal)

Parameters for the `generateEmbeddings` path in `IndexingService`. Applies to all three tools (JSON, CSV, XML).

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `textFields` | `List<String>` | No | `[]` | Field names whose values are concatenated for embedding generation. If empty, no embedding is generated even when `generateEmbeddings=true`. |
| `vectorField` | `String` | No | `"vector"` | Target field name in Solr document for the generated embedding |

**Validation rules:**
- If `generateEmbeddings=true` and `textFields` is null or empty: skip embedding generation for that document (document still indexed without vector field); outcome reported in response.
- If a document is missing all listed `textFields`: skip embedding for that document; report in outcome.
- If `generateEmbeddings=true` and `EmbeddingService.isConfigured()` is false: return error immediately (no documents indexed).

---

## Field Naming Conventions

| Concept | Solr field type | Default field name | Configurable via |
|---------|----------------|-------------------|-----------------|
| Vector embedding | `DenseVectorField` | `vector` | `vectorField` param on `search` / `index-json-documents` |
| Text content | Any Solr text type | user-defined | `textFields` param on `index-json-documents` |

---

## Relationships

```
SearchService
  └── uses EmbeddingService (optional)
  └── uses SolrClient (existing)
  └── produces SearchResponse

EmbeddingService
  └── wraps EmbeddingModel (Spring AI, optional bean)

IndexingService
  └── uses EmbeddingService (optional, for generateEmbeddings path)
  └── uses SolrClient (existing)
  └── uses IndexingDocumentCreator (existing, unchanged)
```

---

## RRF Merge Algorithm

For hybrid mode, the application computes RRF after receiving both result lists:

```
k = 60  (constant)

for each unique document d across both lists:
  keyword_rank  = rank of d in keyword list  (1-based), or ∞ if absent
  semantic_rank = rank of d in semantic list (1-based), or ∞ if absent
  rrf_score(d)  = 1/(k + keyword_rank) + 1/(k + semantic_rank)

Sort by rrf_score descending → take top topK documents
numFound = min(result count, topK)
```
