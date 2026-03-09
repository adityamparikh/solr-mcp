# Implementation Plan: Semantic & Hybrid Search

**Branch**: `002-semantic-hybrid-search` | **Date**: 2026-03-08 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/002-semantic-hybrid-search/spec.md`

## Summary

Extend the existing `search` MCP tool with an optional `mode` parameter (`keyword`/`semantic`/`hybrid`) and a smart server-side default (hybrid when an embedding model is configured, keyword when not). Extend the existing `index-json-documents` MCP tool with an optional `generateEmbeddings` flag to store vector embeddings at index time. Embedding provider is pluggable via Spring AI's `EmbeddingModel` interface; injected optionally via `ObjectProvider<EmbeddingModel>`. Vector similarity search uses Solr's KNN (`{!knn}`) query syntax introduced in Solr 9.0. Hybrid results are merged via Reciprocal Rank Fusion (RRF, k=60).

## Technical Context

**Language/Version**: Java 25 (GraalVM 25+, enforced via Gradle toolchain)
**Primary Dependencies**: Spring Boot 4.0.2, Spring AI 2.0.0-M2 (`spring-ai-core`, `spring-ai-vector-store`), SolrJ 10.0.0, Spring AI starter for chosen embedding provider (e.g., `spring-ai-starter-openai`)
**Storage**: Apache Solr (primary); vector embeddings stored in a `DenseVectorField` within each collection's schema
**Testing**: `./gradlew build`; unit tests (`*Test.java`) with Mockito-mocked Solr; integration tests (`*IntegrationTest.java`) with real Solr via Testcontainers; Docker image tests via `./gradlew dockerIntegrationTest`
**Target Platform**: Linux server (Docker/native image); also macOS for local dev
**Project Type**: MCP server (Spring Boot application, STDIO + HTTP transport modes)
**Performance Goals**: Embedding generation latency dominated by provider; no server-side SLA imposed. KNN search adds one extra Solr round-trip per hybrid call.
**Constraints**: Backward-compatible with all existing `search` and `index-json-documents` call signatures. Semantic/hybrid modes require Solr 9.0+ (KNN not available in 8.11). Smart default silently falls back to keyword when no embedding model is configured.
**Scale/Scope**: Supports all collections in a single Solr deployment; per-collection vector field name is configurable. Embedding model configured once at startup; shared across all calls.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate Question | Status |
|-----------|---------------|--------|
| I. MCP Protocol Integrity | Does this feature write anything to stdout outside of MCP JSON-RPC messages in STDIO mode? | ✅ No — Spring AI embedding calls are HTTP; no stdout side effects |
| I. MCP Protocol Integrity | Are new tools exposed exclusively via `@McpTool` annotations? | ✅ No new tools; new parameters added to existing annotated methods |
| II. Solr Version Compatibility | Have all version-specific Solr API calls been tested or gracefully degraded across versions 8.11, 9.4, 9.9, 9.10, 10? | ⚠️ KNN requires Solr 9.0+. Solr 8.11 must receive a clear error, not a crash. See Complexity Tracking. |
| III. Test-First Development | Are unit tests (`*Test.java`) written with mocked Solr, and integration tests (`*IntegrationTest.java`) with real Solr via Testcontainers? | ☐ Required — write tests before implementation |
| III. Test-First Development | Does `./gradlew build` pass with no test failures? | ☐ Verified at end of each task |
| IV. Security by Default | Do sensitive operations (collection create/delete, schema modification) require authentication in HTTP mode? | ✅ No new sensitive operations; existing `@PreAuthorize("isAuthenticated()")` on `search` covers new parameters |
| V. Simplicity and YAGNI | Is every new abstraction justified by three or more callers / use cases? | ✅ `EmbeddingService` (wraps optional `EmbeddingModel`): called by semantic search, hybrid search, and generateEmbeddings indexing |

## Project Structure

### Documentation (this feature)

```text
specs/002-semantic-hybrid-search/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── search.md        # Updated search tool parameter schema
│   └── index-json-documents.md  # Updated indexing tool parameter schema
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/org/apache/solr/mcp/server/
├── search/
│   ├── SearchService.java               # MODIFY: add mode, topK, vectorField params; smart default
│   ├── SearchResponse.java              # NO CHANGE: existing record works for all modes
│   ├── SearchMode.java                  # NEW: enum KEYWORD, SEMANTIC, HYBRID
│   └── EmbeddingService.java            # NEW: wraps ObjectProvider<EmbeddingModel>; produces float[]
├── indexing/
│   ├── IndexingService.java             # MODIFY: add generateEmbeddings, textFields params to all three tools (index-json-documents, index-csv-documents, index-xml-documents)
│   └── documentcreator/                 # NO CHANGE: existing strategy pattern unchanged
└── config/
    └── EmbeddingConfig.java             # NEW (if needed): expose EmbeddingModel bean conditionally

src/test/java/org/apache/solr/mcp/server/
├── search/
│   ├── SearchServiceTest.java           # MODIFY: add tests for all 3 modes, smart default, error cases
│   ├── SearchServiceSemanticIntegrationTest.java   # NEW: KNN against real Solr 9+ via Testcontainers
│   └── EmbeddingServiceTest.java        # NEW: unit tests for EmbeddingService
├── indexing/
│   └── IndexingServiceTest.java         # MODIFY: add tests for generateEmbeddings path across all three format tools
└── McpToolRegistrationTest.java         # MODIFY: update search method signature reflection

gradle/
└── libs.versions.toml                   # MODIFY: add spring-ai-vector-store dependency
```

**Structure Decision**: Single project (Option 1). All changes are additive to existing `search/` and `indexing/` packages. One new `EmbeddingService` class (3+ callers: semantic, hybrid, generateEmbeddings). No new MCP tools; existing `@McpTool` methods gain optional parameters.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Principle II partial: Solr 8.11 has no KNN support | Semantic/hybrid modes must catch `RemoteSolrException` on Solr 8.11 and return a clear error message ("Semantic search requires Solr 9.0 or later"). Keyword mode and smart fallback on Solr 8.11 (no embedding model configured) are unaffected. | Cannot backport KNN to Solr 8.11; a version check at startup would be premature (version unknown until first query); graceful per-call degradation is the only viable approach. |
