# Phase 0 Research: Semantic & Hybrid Search

**Feature**: 002-semantic-hybrid-search
**Date**: 2026-03-08
**Status**: Complete — all NEEDS CLARIFICATION resolved

---

## 1. Spring AI 2.0.0-M2: Vector Store & Embedding APIs

### Decision
Use `EmbeddingModel` (Spring AI interface) directly for embedding generation rather than `VectorStore`. Inject via `ObjectProvider<EmbeddingModel>` to make the dependency optional.

### Rationale
- `VectorStore` (`AbstractObservationVectorStore`) is designed for stores that own document lifecycle (add/delete/search). Solr MCP does not manage a vector store — it uses Solr as the backing database directly via `SolrClient`. Wrapping a `VectorStore` would require duplicating indexing logic.
- `EmbeddingModel` is the right primitive: it converts text → `float[]` with no lifecycle coupling.
- `ObjectProvider<EmbeddingModel>` allows the server to start with no embedding provider configured (smart default falls back to keyword). If present, `provider.getIfAvailable()` returns the model; if absent, returns `null`.

### Key API Surface (Spring AI 2.0.0-M2)

```java
// Artifact: spring-ai-core (already a transitive dep of spring-ai-starter-*)
interface EmbeddingModel {
    EmbeddingResponse embedForResponse(List<String> texts);  // still present in M2
    float[] embed(String text);                               // convenience overload
}

// ObjectProvider pattern for optional injection:
@Autowired
private ObjectProvider<EmbeddingModel> embeddingModelProvider;

EmbeddingModel model = embeddingModelProvider.getIfAvailable();
boolean embeddingConfigured = (model != null);
```

### Alternatives Considered
- **`spring-ai-vector-store` `VectorStore`**: Rejected — wrong abstraction; forces implementing `doAdd/doDelete/doSimilaritySearch` against SolrClient, duplicating SearchService logic and breaking the clean separation of concerns.
- **Direct HTTP to embedding provider**: Rejected — bypasses Spring AI's provider abstraction; would break FR-009 (switching providers requires only config changes, not code changes).

---

## 2. Solr KNN (Dense Vector) API

### Decision
Use Solr's `{!knn}` local params query syntax for semantic search. Issue KNN queries via `SolrQuery` using the existing `SolrClient` pattern.

### Rationale
Solr 9.0 introduced `DenseVectorField` and KNN support. All target Solr versions ≥ 9.0 support this natively. The existing `SolrClient` in `SearchService` is already capable of issuing these queries.

### Solr 8.11 Compatibility
**Solr 8.11 does NOT support KNN.** `DenseVectorField` was introduced in Solr 9.0. When a semantic or hybrid query is issued against Solr 8.11, a `RemoteSolrException` is returned. The implementation must catch this and return a human-readable error: "Semantic and hybrid search require Solr 9.0 or later. Your Solr version does not support vector search."

The smart default behavior on Solr 8.11 without an embedding model is keyword-only — identical to pre-2.0.0. No impact.

### Key API Surface

```java
// Semantic-only query
SolrQuery knnQuery = new SolrQuery();
knnQuery.setQuery("{!knn f=" + vectorField + " topK=" + topK + "}" + vectorToString(embedding));
knnQuery.addFilterQuery(filterQueries);
QueryResponse response = solrClient.query(collection, knnQuery);

// numFound for KNN = results returned (≤ topK), not corpus total
long numFound = response.getResults().size();  // NOT response.getResults().getNumFound()

// Hybrid: issue keyword + KNN queries independently, merge via RRF
```

### POST method for large vectors
For high-dimensional vectors (>512 dimensions), the query string may exceed URL length limits. Use `SolrRequest.METHOD.POST` on the `QueryRequest`:

```java
QueryRequest req = new QueryRequest(knnQuery, SolrRequest.METHOD.POST);
QueryResponse response = req.process(solrClient, collection);
```

### Reciprocal Rank Fusion (RRF)

RRF is the merging algorithm for hybrid mode. Given two ranked lists (keyword results, semantic results), each document's score is:

```
RRF_score(d) = Σ 1 / (k + rank(d, list_i))
```

where k=60 (standard constant that smooths rank differences). Documents appearing in both lists score higher than those in only one. This requires no additional Solr feature — it is computed in application code.

### Alternatives Considered
- **Solr's built-in `rrf` query parser**: Available in Solr 9.4+ but not 9.0. Using application-level RRF ensures compatibility across all Solr 9+ versions.
- **Solr's `{!edismax}` + vector boost**: Not supported; KNN must be issued as a separate query.

---

## 3. Tool Surface: No Single "index" Tool in Codebase

### Discovery
The spec references "the existing `index` tool" but the codebase has THREE separate MCP tools:
- `index-json-documents` (IndexingService.java, `indexJsonDocuments` method)
- `index-csv-documents` (IndexingService.java, `indexCsvDocuments` method)
- `index-xml-documents` (IndexingService.java, `indexXmlDocuments` method)

### Decision
`generateEmbeddings` and `textFields` parameters are added to **all three tools**.

### Rationale
All three format-specific parsers (`JsonDocumentCreator`, `CsvDocumentCreator`, `XmlDocumentCreator`) produce `List<SolrInputDocument>` with field names preserved:
- **JSON**: Field names come from JSON object keys (nested keys flattened with `_`)
- **CSV**: Field names come from column headers in the first row
- **XML**: Field names come from element names (nested elements flattened with `_`, attributes get `_attr` suffix)

The `textFields` concatenation approach — collect values for the named fields from each `SolrInputDocument`, concatenate, embed — works identically for all three formats. Restricting to JSON-only would force users to convert data formats unnecessarily, providing no technical benefit.

### Impact
FR-005 and FR-006 in the spec apply to all three indexing tools. Contracts are generated for each.

---

## 4. McpToolRegistrationTest Impact

### Discovery
`McpToolRegistrationTest.testSearchServiceHasToolAnnotation` uses reflection to find the `search` method by exact parameter types `(String, String, List, List, List, Integer, Integer)`. Adding new parameters to `SearchService.search(...)` will break this test.

### Decision
Update `McpToolRegistrationTest` to look up the `search` method by name (not by exact parameter signature), or update the expected signature to include the new parameters. Prefer updating the signature check to the new full signature — it is the most precise test and guards against accidental parameter removal.

### Alternatives Considered
- **Method lookup by name only**: Weaker assertion — doesn't verify parameter types, reducing guard value.
- **Separate test for new params**: Would split test coverage and create confusion about which assertion is authoritative.

---

## 5. New Gradle Dependency Required

`spring-ai-vector-store` is needed for `AbstractObservationVectorStore` in case future phases add a full VectorStore implementation. For Phase 1, only `spring-ai-core` (already present transitively) is strictly needed for `EmbeddingModel`. However, adding `spring-ai-vector-store` now future-proofs without over-engineering — it is part of the Spring AI BOM already managed by the project.

Add to `gradle/libs.versions.toml`:
```toml
[libraries]
spring-ai-vector-store = { module = "org.springframework.ai:spring-ai-vector-store" }
```

And to `build.gradle.kts`:
```kotlin
implementation(libs.spring.ai.vector.store)
```

---

## 6. EmbeddingService Design

### Decision
Create `EmbeddingService` as a thin Spring `@Service` that wraps `ObjectProvider<EmbeddingModel>`:

```java
@Service
public class EmbeddingService {
    private final EmbeddingModel embeddingModel;  // null if not configured

    public EmbeddingService(ObjectProvider<EmbeddingModel> provider) {
        this.embeddingModel = provider.getIfAvailable();
    }

    public boolean isConfigured() { return embeddingModel != null; }

    public float[] embed(String text) {
        if (embeddingModel == null) throw new EmbeddingNotConfiguredException(...);
        return embeddingModel.embed(text);
    }
}
```

### Callers (justifies abstraction per Principle V)
1. `SearchService` — semantic mode (embed query, issue KNN)
2. `SearchService` — hybrid mode (embed query, issue KNN + keyword, merge)
3. `IndexingService` — `generateEmbeddings=true` (embed text fields, store in document)

Three distinct callers — abstraction justified under Principle V (YAGNI).

---

## Summary of Resolved Decisions

| Unknown | Resolution |
|---------|------------|
| Spring AI VectorStore vs EmbeddingModel | Use `EmbeddingModel` directly; inject via `ObjectProvider` |
| Solr KNN syntax | `{!knn f=<field> topK=<n>}<float[]>` via existing `SolrClient` |
| Solr 8.11 KNN gap | Catch `RemoteSolrException`, return clear error message |
| Hybrid merge algorithm | Reciprocal Rank Fusion (k=60) in application code |
| "index tool" ambiguity | `generateEmbeddings` applies to all three tools (JSON, CSV, XML) |
| McpToolRegistrationTest | Update to include new `search` method signature |
| New dependency | Add `spring-ai-vector-store` to `libs.versions.toml` |
| `numFound` semantics for KNN | Use `results.size()` (not `getNumFound()`); equals actual returned count |
