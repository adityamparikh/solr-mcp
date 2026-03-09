# Quickstart: Semantic & Hybrid Search

**Feature**: 002-semantic-hybrid-search
**Audience**: Developers implementing or testing this feature

---

## Prerequisites

1. **Solr 9.0+** running locally or via Testcontainers (KNN is not available on Solr 8.11)
2. **Embedding model configured** — at minimum, an OpenAI API key and the `spring-ai-starter-openai` dependency on the classpath
3. **Solr collection with DenseVectorField** — the collection schema must include a vector field (e.g., `vector`) of type `DenseVectorField`

---

## Step 1: Configure the Embedding Provider

Add to `application.properties` (or environment variables):

```properties
# OpenAI example (replace with any supported Spring AI provider)
spring.ai.openai.api-key=${OPENAI_API_KEY}
spring.ai.openai.embedding.options.model=text-embedding-3-small
```

If no embedding model is configured, the server starts normally and defaults to keyword-only search.

---

## Step 2: Add a Vector Field to the Solr Schema

Before indexing documents with embeddings, add a `DenseVectorField` to the collection schema. Example using Solr Schema API:

```bash
curl -X POST http://localhost:8983/solr/<collection>/schema \
  -H 'Content-Type: application/json' \
  -d '{
    "add-field-type": {
      "name": "knn_vector_768",
      "class": "solr.DenseVectorField",
      "vectorDimension": 768,
      "similarityFunction": "cosine"
    },
    "add-field": {
      "name": "vector",
      "type": "knn_vector_768",
      "stored": false,
      "indexed": true
    }
  }'
```

Set `vectorDimension` to match your embedding model's output dimension (e.g., 1536 for `text-embedding-3-small`).

---

## Step 3: Index Documents with Embeddings

Call the `index-json-documents` MCP tool with `generateEmbeddings=true`:

```json
{
  "tool": "index-json-documents",
  "arguments": {
    "collection": "my_collection",
    "documents": "[{\"id\": \"1\", \"title\": \"Renewable energy policy\", \"body\": \"Solar and wind power reduce carbon emissions.\"}, {\"id\": \"2\", \"title\": \"Budget accommodation guide\", \"body\": \"Affordable hotels and hostels for travelers.\"}]",
    "generateEmbeddings": true,
    "textFields": ["title", "body"]
  }
}
```

The server concatenates `title` + `body` for each document, generates an embedding, and stores it in the `vector` field.

---

## Step 4: Run a Semantic Search

Call `search` without `mode` — the smart default applies hybrid automatically:

```json
{
  "tool": "search",
  "arguments": {
    "collection": "my_collection",
    "query": "affordable accommodation",
    "topK": 5
  }
}
```

Expected result: document 2 ("Budget accommodation guide") appears even though the query "affordable accommodation" does not exactly match the document's text.

---

## Step 5: Run an Explicit Semantic-Only Search

```json
{
  "tool": "search",
  "arguments": {
    "collection": "my_collection",
    "query": "renewable energy policy",
    "mode": "semantic",
    "topK": 10,
    "vectorField": "vector"
  }
}
```

---

## Step 6: Run a Hybrid Search with Filters

```json
{
  "tool": "search",
  "arguments": {
    "collection": "my_collection",
    "query": "energy",
    "mode": "hybrid",
    "topK": 10,
    "filterQueries": ["category:environment"],
    "facetFields": ["category"]
  }
}
```

---

## Error Cases

| Situation | Tool response |
|-----------|--------------|
| `mode=semantic` with no embedding model | `"Semantic/hybrid search requires an embedding model to be configured."` |
| `mode=hybrid` against Solr 8.11 | `"Semantic search requires Solr 9.0 or later."` |
| `generateEmbeddings=true` with no embedding model | `"Embedding generation requires an embedding model to be configured."` |
| Invalid `mode` value (e.g., `"fuzzy"`) | `"Invalid mode 'fuzzy'. Accepted values: keyword, semantic, hybrid."` |
| All `textFields` missing from a document | Document indexed without vector field; counted in `skipped` |

---

## Running Tests

```bash
# Unit tests (mocked Solr, no embedding model required)
./gradlew test --tests "SearchServiceTest"
./gradlew test --tests "EmbeddingServiceTest"

# Integration tests (real Solr 9+ via Testcontainers)
./gradlew test --tests "SearchServiceSemanticIntegrationTest"

# Against a specific Solr version
./gradlew test -Dsolr.test.image=solr:9.4-slim --tests "*IntegrationTest"
./gradlew test -Dsolr.test.image=solr:10-slim  --tests "*IntegrationTest"

# Full build
./gradlew build
```

---

## Key Design Decisions

- **Smart default**: When `mode` is omitted, the server selects `hybrid` if an embedding model is available, `keyword` if not. No AI reasoning required — always produces the best available result.
- **numFound for KNN**: Reports actual results returned (≤ topK), not total corpus size. This differs intentionally from keyword mode.
- **generateEmbeddings on JSON only**: CSV and XML tools are unchanged; only `index-json-documents` supports embedding generation.
- **Solr 8.11**: Keyword search and all pre-2.0.0 behavior work unchanged. Semantic/hybrid returns a clear error.
