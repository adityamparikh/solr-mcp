# Feature Specification: Semantic & Hybrid Search

**Feature Branch**: `002-semantic-hybrid-search`
**Created**: 2026-03-08
**Status**: Draft
**Input**: docs/SEMANTIC_HYBRID_SEARCH_SPEC.md — semantic and hybrid search for Solr MCP 2.0.0

## Clarifications

### Session 2026-03-08

- Q: How should `numFound` be handled in semantic/hybrid search responses, given that vector search cannot report a total corpus-wide match count? → A: `numFound` equals the count of results actually returned (≤ topK); this differs intentionally from keyword search's total-corpus count and must be documented as a known behavioral difference.
- Q: Should vector/semantic/hybrid search be exposed as separate new MCP tools, or as optional parameters on the existing `search` and `index` tools? → A: Option B — extend the existing `search` tool with an optional `mode` parameter (`keyword`/`semantic`/`hybrid`) and the existing `index` tool with an optional `generateEmbeddings` flag. See Constraints & Tradeoffs for full rationale.
- Q: How will the system determine which search mode to apply when `mode` is not explicitly specified — via AI-interpreted tool description, or via server-side logic? → A: Smart server default — the server selects `hybrid` automatically when an embedding model is configured, `keyword` when none is configured. No AI reasoning required for mode selection; callers omitting `mode` always get the best available result. See Constraints & Tradeoffs for full rationale.
- Q: Should embedding-aware document indexing be a new separate tool or an extension of the existing `index` tool? → A: Extension of the existing `index` tool via an optional `generateEmbeddings` flag, consistent with the search tool surface decision. See Constraints & Tradeoffs for full rationale.
- Q: Should `generateEmbeddings` apply only to `index-json-documents`, or to all three indexing tools (JSON, CSV, XML)? → A: All three tools. All three format-specific parsers (`JsonDocumentCreator`, `CsvDocumentCreator`, `XmlDocumentCreator`) produce `SolrInputDocument` objects with field names preserved (JSON keys, CSV column headers, XML element names). The `textFields` concatenation approach works identically for all three formats. Restricting to JSON-only would be an arbitrary limitation that forces users to convert data formats unnecessarily.

### Session 2026-03-22

- Q: Is `SolrVectorStore` (Spring AI `VectorStore` implementation) a required deliverable, or just an implementation detail? → A: **Required deliverable.** The `SolrVectorStore` is the foundation that enables Spring AI's advisor API (`QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`) for RAG workflows. Without it, consumers are limited to the MCP tool surface and cannot use Solr as a vector database in standard Spring AI applications. Added as FR-008a/b/c and as a Key Entity.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Zero-Configuration Best-Quality Search (Priority: P1)

A non-technical user in Claude Desktop types "find documents about renewable energy policy." Claude calls the `search` tool with no `mode` parameter. Because an embedding model is configured, the server automatically applies hybrid search — combining keyword precision with semantic recall — and returns the best available results. Neither the user nor Claude needed to reason about which search mode to use.

**Why this priority**: This is the primary end-user value proposition. The smart server default means non-technical users get optimal results without any AI reasoning or tool-selection logic. It also means the feature works correctly even if Claude's tool selection is imperfect.

**Independent Test**: Can be fully tested by calling the `search` tool with no `mode` parameter against a collection with vector embeddings and an embedding model configured, and verifying that the results include both keyword-matched and semantically-similar documents (e.g., "affordable accommodation" returns "budget hotel" documents).

**Acceptance Scenarios**:

1. **Given** an embedding model is configured and a collection has documents with vector embeddings, **When** the `search` tool is called with no `mode` parameter, **Then** the server applies `hybrid` mode automatically and returns a merged ranked list of keyword and semantic results.
2. **Given** the `search` tool called explicitly with `mode=hybrid`, **When** an embedding model is configured, **Then** the tool executes both keyword and semantic searches, merges results, and returns a ranked list.
3. **Given** the `search` tool called with `mode=semantic`, **When** an embedding model is configured, **Then** the tool returns a ranked list of documents by semantic similarity only.
4. **Given** the `search` tool called with `mode=hybrid` or `mode=semantic` and an embedding model is not configured, **Then** the tool returns a clear error message explaining that an embedding model is required for that mode.
5. **Given** the `search` tool called with `mode=hybrid` and filter criteria, **When** results are returned, **Then** results are restricted to documents matching both the rank fusion score and the filter conditions.
6. **Given** the `search` tool called with `mode=semantic` and `topK=5`, **When** the collection has more than 5 matching documents, **Then** only the 5 nearest neighbors are returned and `numFound` equals the number of results returned (not the total collection size).

---

### User Story 2 - Unchanged Behavior Without Embedding Model (Priority: P1)

An existing operator upgrades to 2.0.0 without configuring an embedding model. All existing calls to `search` and `index` — with no new parameters — behave identically to pre-2.0.0. The server's smart default falls back to `keyword` when no embedding model is available, preserving existing behavior exactly.

**Why this priority**: The smart default must not silently degrade existing deployments. An operator who does not opt into embedding capabilities must experience zero behavioral change.

**Independent Test**: Can be fully tested by starting the server with no embedding configuration and verifying that every existing `search` and `index` call produces responses identical to pre-2.0.0.

**Acceptance Scenarios**:

1. **Given** no embedding model is configured, **When** the `search` tool is called with no `mode` parameter, **Then** the server applies `keyword` mode automatically — identical to pre-2.0.0 behavior.
2. **Given** no embedding model is configured, **When** the server starts, **Then** it starts successfully with no errors and all existing tools are available.
3. **Given** no embedding model is configured and `search` is called with `mode=hybrid` or `mode=semantic`, **Then** the tool returns a clear error; it does not silently fall back to keyword.
4. **Given** the server running in HTTP mode with OAuth2 configured, **When** any search or index call uses new parameters (`mode`, `generateEmbeddings`) without authentication, **Then** the call is rejected with an authentication error.

---

### User Story 3 - Index Documents with Auto-Generated Embeddings (Priority: P2)

An AI assistant indexes documents (JSON, CSV, or XML format) and sets `generateEmbeddings=true` on the appropriate `index` tool. The tool automatically generates vector embeddings from specified text fields and stores them alongside each document, making them immediately discoverable via the default smart search.

**Why this priority**: Without indexed embeddings, the smart hybrid default has no vector component to draw from. This is the data ingestion counterpart to semantic and hybrid search.

**Independent Test**: Can be tested end-to-end by calling any of the three `index` tools (`index-json-documents`, `index-csv-documents`, `index-xml-documents`) with `generateEmbeddings=true`, then immediately calling `search` with no `mode` parameter (triggering the smart hybrid default) and verifying that the indexed documents appear in the results.

**Acceptance Scenarios**:

1. **Given** any of the three `index` tools (`index-json-documents`, `index-csv-documents`, `index-xml-documents`) called with a document payload, a list of text fields, and `generateEmbeddings=true`, **When** the call is made, **Then** each document is stored in Solr with an automatically generated vector embedding derived from the specified text fields.
2. **Given** documents indexed with `generateEmbeddings=true`, **When** `search` is subsequently called (with any mode that uses vectors), **Then** those documents appear in the results.
3. **Given** documents with missing text fields, **When** indexed with `generateEmbeddings=true`, **Then** the missing fields are skipped and the document is indexed with embeddings generated from the fields that are present.
4. **Given** the `index` tool called without `generateEmbeddings`, **When** any documents are indexed, **Then** behavior is identical to pre-2.0.0 — no embeddings are generated.
5. **Given** `generateEmbeddings=true` with no embedding model configured, **When** `index` is called, **Then** the tool returns a clear error.
6. **Given** a successful indexing call with `generateEmbeddings=true`, **When** the tool returns, **Then** the count of successfully indexed documents is reported.

---

### Edge Cases

- What happens when the smart default selects `hybrid` but the collection's schema has no vector field? The tool must surface a meaningful error from the search engine rather than a generic failure.
- What happens when the embedding provider service is temporarily unavailable during a hybrid or semantic search? The tool must return a clear error indicating the provider is unreachable, not a silent timeout.
- What happens when all of a document's specified text fields are empty or null during `generateEmbeddings=true` indexing? The document must be skipped or indexed without a vector, and the outcome must be reported.
- What happens when `topK` exceeds the number of documents in the collection during semantic search? The tool must return all available documents without error; `numFound` reflects the actual count returned.
- What happens when two collections have different vector field dimensions? Each collection must be independently configurable; a dimension mismatch must surface as a clear error.
- What happens when an invalid `mode` value is supplied (e.g., `mode=fuzzy`)? The tool must return a clear validation error listing the accepted values (`keyword`, `semantic`, `hybrid`).
- What happens when an embedding model is configured but later removed while the server is running? The smart default must detect the absence and fall back to `keyword` rather than throwing an unhandled error.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The existing `search` tool MUST accept an optional `mode` parameter with values `keyword`, `semantic`, and `hybrid`. When `mode` is not specified, the server MUST apply a smart default: `hybrid` if an embedding model is configured, `keyword` if not.
- **FR-002**: When `mode=semantic` (explicit or smart-defaulted), the `search` tool MUST execute a vector similarity search using the natural language query text and return a ranked list of semantically similar documents.
- **FR-003**: When `mode=hybrid` (explicit or smart-defaulted), the `search` tool MUST execute both a keyword search and a semantic search independently, merge the results using a ranking algorithm that favors documents appearing in both result sets, and return a single unified ranked list.
- **FR-004**: When `mode=keyword` (explicit) or when the smart default selects keyword (no embedding model), the `search` tool MUST execute BM25 keyword search with behavior identical to pre-2.0.0.
- **FR-005**: All three indexing tools (`index-json-documents`, `index-csv-documents`, `index-xml-documents`) MUST accept an optional `generateEmbeddings` flag and an optional `textFields` parameter. When `generateEmbeddings` is not specified or false, behavior is identical to pre-2.0.0. All three format parsers (JSON, CSV, XML) preserve field names in the resulting documents, making `textFields` concatenation equally applicable to all formats.
- **FR-006**: When `generateEmbeddings=true`, each indexing tool MUST generate embedding vectors from the specified `textFields` and store them alongside each document in the collection, regardless of the input format (JSON, CSV, or XML).
- **FR-007**: The `search` tool called with an explicit `mode=semantic` or `mode=hybrid`, and the `index` tool called with `generateEmbeddings=true`, MUST return a clear, actionable error message when no embedding model is configured. The smart default MUST NOT surface this error — it silently falls back to `keyword`.
- **FR-008**: The system MUST start and all existing tool calls MUST function correctly when no embedding model is configured.
- **FR-008a**: The system MUST implement Spring AI's `VectorStore` interface as `SolrVectorStore` (extending `AbstractObservationVectorStore`), enabling Spring AI's advisor API (e.g., `QuestionAnswerAdvisor`, `RetrievalAugmentationAdvisor`) to use Solr as a vector database backend. This is the foundation on which semantic search capabilities are built — MCP tools delegate to `SolrVectorStore` rather than implementing vector operations directly against `SolrClient`.
- **FR-008b**: The system MUST provide a `VectorStoreFactory` that creates and caches `SolrVectorStore` instances per Solr collection using a thread-safe cache, since MCP tools accept `collection` as a per-call parameter and each `SolrVectorStore` is bound to a single collection at construction time.
- **FR-008c**: The `SolrVectorStore` MUST convert Spring AI `Filter.Expression` to Solr query syntax (EQ, NE, GT, GTE, LT, LTE, AND, OR, IN) with automatic metadata prefix handling, so that Spring AI advisor filter expressions work transparently against Solr.
- **FR-008d**: When a Solr collection's schema does not contain the configured vector field (e.g., `DenseVectorField`), the `SolrVectorStore` MUST degrade gracefully: `doAdd()` MUST skip embedding generation and index documents with content and metadata only; `doSimilaritySearch()` MUST fall back to keyword search on the content field instead of failing. The vector field check MUST be performed via the Solr Schema API and cached per instance.
- **FR-009**: The system MUST support any compliant embedding provider configurable at startup; switching providers MUST require only configuration changes, not code changes.
- **FR-010**: The `search` tool MUST accept an optional configurable vector field name parameter to support collections with custom schema configurations when executing semantic or hybrid search.
- **FR-011**: The `search` tool in semantic or hybrid mode MUST support optional filter criteria to restrict results.
- **FR-012**: The `search` tool in hybrid mode MUST support optional facet fields, applied to the keyword leg of the search.
- **FR-013**: All new parameters (`mode`, `generateEmbeddings`, `textFields`, `topK`, vector field name) MUST be optional with backward-compatible defaults; no existing call signatures are broken.
- **FR-014**: The `search` and `index` tools MUST enforce the same authentication requirements as before; the new parameters do not alter security behavior.
- **FR-015**: The `search` tool in semantic or hybrid mode MUST report `numFound` as the count of results actually returned (≤ topK), not as a total corpus-wide match count. The tool description MUST document this behavioral difference from keyword mode.

### Key Entities

- **Search Mode**: The retrieval strategy applied by the `search` tool: `keyword` (BM25), `semantic` (vector similarity), or `hybrid` (both merged via rank fusion). Selected explicitly via the `mode` parameter, or automatically via the smart server default.
- **Smart Default**: Server-side logic that selects the search mode when `mode` is omitted — `hybrid` if an embedding model is configured, `keyword` if not. Requires no AI reasoning; always produces the best available result.
- **Embedding**: A fixed-length numerical representation of text content that captures semantic meaning, enabling similarity comparison between documents and queries.
- **Vector Field**: A specially configured field in a Solr collection schema that stores embeddings and supports approximate nearest-neighbor search.
- **Embedding Provider**: An external service or local model that converts text into embeddings; configured once at server startup and available to the smart default, semantic/hybrid search modes, and the `generateEmbeddings` indexing flag.
- **Search Response**: The unified result format returned by the `search` tool across all modes — a ranked list of documents with optional facet counts and a result count. For keyword mode, the result count reflects total corpus matches; for semantic and hybrid modes, it reflects the number of results returned (≤ topK).
- **SolrVectorStore**: The Spring AI `VectorStore` implementation for Apache Solr. Extends `AbstractObservationVectorStore` to provide Micrometer observation support. Handles embedding generation, KNN query construction, filter expression conversion, and document marshaling between Spring AI `Document` and Solr `SolrInputDocument` formats. Configurable via `SolrVectorStoreOptions`. This is the foundational component that enables Spring AI's advisor API (RAG workflows) to use Solr as a vector database.
- **VectorStoreFactory**: A per-collection cache of `SolrVectorStore` instances. Since each `SolrVectorStore` is bound to a single Solr collection, and MCP tools accept `collection` as a per-call parameter, the factory uses `ConcurrentHashMap.computeIfAbsent` for atomic, thread-safe instance creation and reuse.
- **Hybrid Rank Score**: A combined relevance score derived from a document's position in both the keyword and semantic result lists, used to produce the final merged ordering in hybrid mode.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Calling `search` with no `mode` parameter when an embedding model is configured returns hybrid results — including semantically relevant documents that contain zero query keywords.
- **SC-002**: Hybrid mode returns a combined result set where documents relevant to both keyword and semantic criteria are ranked ahead of documents relevant to only one, verifiable by at least one controlled test with known ground truth.
- **SC-003**: Calling `search` with no `mode` parameter when no embedding model is configured returns keyword results identical to pre-2.0.0 — zero behavioral regression for operators who do not configure embeddings.
- **SC-004**: Documents indexed with `generateEmbeddings=true` are retrievable via the smart default (no `mode` specified) within the same test session without any additional steps.
- **SC-005**: Explicitly requesting `mode=semantic` or `mode=hybrid` without an embedding model configured produces a clear, human-readable error message (not a stack trace).
- **SC-006**: All search modes and indexing behavior pass automated tests against every supported Solr version in the compatibility matrix.
- **SC-007**: Tool calls using new parameters without valid authentication credentials in HTTP mode with security enabled result in an authentication error — no data is returned.

## Constraints & Tradeoffs

### Tool Surface Design Decision

Three options were considered for exposing semantic and hybrid search capabilities:

**Option A — Rejected**: Separate new tools (`semantic-search`, `hybrid-search`, `index-with-embeddings`). When a non-technical user types a natural language request in an MCP client, Claude must choose between multiple similarly-named tools and may default to the existing `search` (keyword) tool, silently delivering inferior results. Discoverability of the right tool is fragile.

**Option B ✅ — Selected**: Extend existing `search` with optional `mode` parameter; extend existing `index` with optional `generateEmbeddings` flag. See rationale below.

**Option C — Rejected**: Separate `semantic-search` and `hybrid-search` tools; extend existing `index` with `generateEmbeddings`. Inherits Option A's tool-selection problem for search, and is internally inconsistent (search is split, index is unified).

**Rationale for Option B:** Non-technical users of MCP clients like Claude Desktop interact through natural language — they never directly invoke tool parameters. Claude, acting as the AI intermediary, reads tool descriptions and selects the appropriate call. With a single `search` tool: (1) Claude has only one tool to call — no ambiguous multi-tool selection; (2) the smart default ensures optimal results without requiring Claude to reason about mode; (3) additive parameters with backward-compatible defaults mean existing integrations require zero changes; (4) operators and developers reason about one search tool and one index tool, not a proliferation of names.

---

### Mode Selection Mechanism

Three options were considered for determining which search mode to apply when the caller does not specify `mode`:

**Option A — Rejected**: Always default to `keyword`; rely on the MCP tool description to instruct Claude to pass `mode=hybrid` for general queries. This is fragile — it requires Claude to reason correctly about mode selection on every call. If Claude's tool-selection logic changes, or if the server is called directly without Claude, keyword results are silently returned instead of the best available quality.

**Option B ✅ — Selected**: Smart server default — the server selects `hybrid` when an embedding model is configured, `keyword` when not. Callers omitting `mode` always receive the best result the server can produce. No AI reasoning required.

**Option C — Rejected**: Require explicit `mode` on every call. Breaking change — all existing callers must be updated.

**Rationale for Option B:** Placing the mode-selection logic in the server, not in the AI assistant, makes the behavior deterministic, testable, and consistent regardless of which AI model or client calls the tool. It also correctly models the semantic of "best available search" — if embeddings are present, hybrid is objectively better; if not, keyword is the only option. The smart default also handles the edge case where an embedding model is removed at runtime: the server detects absence and falls back gracefully to keyword, rather than requiring every caller to handle this scenario.

The one deliberate behavioral change this introduces for 2.0.0: operators who configure an embedding model and call `search` without `mode` will now receive hybrid results instead of keyword results. This is intentional and beneficial — they opted into embedding capabilities, so improved search quality is the expected outcome. Operators who want explicit keyword results must pass `mode=keyword`.

---

### Indexing Tool Surface Decision

Two options were considered for exposing embedding-aware document indexing:

**Option A — Rejected**: Separate new tool `index-with-embeddings`. Introduces a second indexing tool that users must know to invoke specifically when they want embeddings. A non-technical user who says "index these documents so I can search them semantically" would need Claude to reason about which index tool to pick — the same fragile tool-selection problem rejected for search.

**Option B ✅ — Selected**: Extend the existing `index` tool with an optional `generateEmbeddings` flag and `textFields` parameter.

**Rationale for Option B:** Consistency with the search tool surface decision is the primary driver. A single `index` tool preserves a simple mental model: "index this document" always uses the same tool, regardless of whether embeddings are generated. The `generateEmbeddings` flag is an opt-in capability, not a separate workflow. Non-technical users who say "index this and make it semantically searchable" can be served by Claude passing `generateEmbeddings=true` on the familiar `index` tool — no new tool name to discover. Existing indexing calls are entirely unaffected.

## Assumptions

- The Solr collection targeted by semantic or hybrid search already has a vector field defined in its schema. Schema setup is an operator prerequisite, not a responsibility of the MCP server.
- Embedding generation latency is dominated by the external embedding provider; no server-side performance SLA is imposed on embedding calls.
- Vector dimensionality is consistent within a single collection — all documents and queries use the same number of dimensions.
- The number of distinct Solr collections in a deployment is bounded to a manageable size (tens to low hundreds), making an in-memory per-collection cache acceptable.
- Solr 9.0+ is required for semantic and hybrid modes; Solr 8.x users invoking these modes will receive a Solr-level error. Keyword mode and all existing behavior are unaffected on any supported Solr version.
- Vector search is inherently approximate (top-K nearest neighbors); `numFound` for semantic and hybrid modes reflects results returned, not total matching documents. This is a known and accepted behavioral difference from keyword mode.
- Operators who configure an embedding model accept that the `search` tool's default behavior upgrades to hybrid in 2.0.0. This is a documented, intentional behavioral change in the major version release.
