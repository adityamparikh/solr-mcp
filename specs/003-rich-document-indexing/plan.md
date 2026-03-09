# Implementation Plan: Rich Document Indexing

**Branch**: `003-rich-document-indexing` | **Date**: 2026-03-08 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/003-rich-document-indexing/spec.md`

## Summary

Add two new MCP tools to `IndexingService`: `index-rich-document` (converts PDF/DOCX/PPTX/XLSX/HTML/image files to markdown via Docling Serve, then indexes) and `index-markdown` (indexes markdown strings directly with frontmatter parsing). Docling Serve (`quay.io/docling-project/docling-serve`) is a required external service for `index-rich-document`, configured via `DOCLING_SERVE_URL`. The Java client is `ai.docling:docling-serve-client`. `DoclingService` wraps the optional client (injected via `ObjectProvider`). `MarkdownDocumentCreator` is a new fourth implementation of `SolrDocumentCreator` for direct markdown indexing.

## Technical Context

**Language/Version**: Java 25 (GraalVM 25+, enforced via Gradle toolchain)
**Primary Dependencies**: Spring Boot 4.0.2, Spring AI 2.0.0-M2, SolrJ 10.0.0; `com.ibm.docling:docling4j:0.1.1` (in-process Python via GraalPy; JVM mode only)
**Storage**: Apache Solr — documents indexed as single Solr records with `content` (markdown text), `source`, `format`, and optional frontmatter fields
**Testing**: `./gradlew build`; unit tests (`*Test.java`) with mocked `DoclingService` and mocked Solr; integration tests (`*IntegrationTest.java`) with real Solr via Testcontainers; docling4j invoked in-process (no Docker sidecar needed for integration tests)
**Target Platform**: Linux server (Docker/native image); STDIO + HTTP transport modes
**Project Type**: MCP server extension (additive to existing `IndexingService`)
**Performance Goals**: No server-side SLA on conversion; Docling conversion latency is provider-dependent. Configurable timeout (default 120s).
**Constraints**: `index-rich-document` requires Docling Serve; server must start cleanly without it. `index-markdown` has no external dependencies. Max local file size: 50 MB (configurable). Backward-compatible with all existing tool call signatures.
**Scale/Scope**: One Docling Serve URL configured per server instance; shared across all `index-rich-document` calls. Each call converts and indexes one document.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate Question | Status |
|-----------|---------------|--------|
| I. MCP Protocol Integrity | Does this feature write anything to stdout outside of MCP JSON-RPC messages in STDIO mode? | ✅ No — Docling Serve calls are outbound HTTP; no stdout side effects |
| I. MCP Protocol Integrity | Are new tools exposed exclusively via `@McpTool` annotations? | ✅ Yes — `index-rich-document` and `index-markdown` use `@McpTool` |
| II. Solr Version Compatibility | Have all version-specific Solr API calls been tested or gracefully degraded across versions 8.11, 9.4, 9.9, 9.10, 10? | ✅ No new Solr API calls — uses existing `SolrClient.add()` and `commit()` which work on all versions |
| III. Test-First Development | Are unit tests (`*Test.java`) written with mocked Solr, and integration tests (`*IntegrationTest.java`) with real Solr via Testcontainers? | ☐ Required — write tests before implementation |
| III. Test-First Development | Does `./gradlew build` pass with no test failures? | ☐ Verified at end of each task |
| IV. Security by Default | Do sensitive operations (collection create/delete, schema modification) require authentication in HTTP mode? | ✅ Both new tools carry `@PreAuthorize("isAuthenticated()")` |
| V. Simplicity and YAGNI | Is every new abstraction justified by three or more callers / use cases? | ✅ `MarkdownDocumentCreator`: 4th SolrDocumentCreator (pattern justified by 4 impls). `DoclingService`: isolates testable external-service boundary; follows same pattern as `EmbeddingService` in feature 002 |

**Constitution Check post-design**: ✅ All gates pass.

## Project Structure

### Documentation (this feature)

```text
specs/003-rich-document-indexing/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── index-rich-document.md
│   └── index-markdown.md
└── tasks.md             # Phase 2 output (/speckit.tasks — NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
src/main/java/org/apache/solr/mcp/server/
├── indexing/
│   ├── IndexingService.java                  # MODIFY: add indexRichDocument, indexMarkdown methods
│   ├── DoclingService.java                   # NEW: wraps optional DoclingServeClient; converts docs to markdown
│   └── documentcreator/
│       ├── SolrDocumentCreator.java          # NO CHANGE: existing interface
│       ├── JsonDocumentCreator.java          # NO CHANGE
│       ├── CsvDocumentCreator.java           # NO CHANGE
│       ├── XmlDocumentCreator.java           # NO CHANGE
│       ├── MarkdownDocumentCreator.java      # NEW: 4th SolrDocumentCreator; parses YAML frontmatter
│       ├── IndexingDocumentCreator.java      # MODIFY: register MarkdownDocumentCreator
│       └── FieldNameSanitizer.java           # NO CHANGE

src/test/java/org/apache/solr/mcp/server/
├── indexing/
│   ├── IndexingServiceRichDocTest.java                # NEW: unit tests (mocked Docling + Solr)
│   ├── IndexingServiceRichDocIntegrationTest.java     # NEW: integration tests (Testcontainers Solr + Docling)
│   └── documentcreator/
│       └── MarkdownDocumentCreatorTest.java           # NEW: unit tests for frontmatter parsing
└── McpToolRegistrationTest.java                       # MODIFY: assert index-rich-document, index-markdown registered

gradle/
└── libs.versions.toml                        # MODIFY: add com.ibm.docling:docling4j
```

**Structure Decision**: Single project (Option 1). All changes are additive to `indexing/`. `DoclingService` follows the same optional-bean pattern as `EmbeddingService` (feature 002). `MarkdownDocumentCreator` is the fourth `SolrDocumentCreator` implementation, fully satisfying Constitution Principle V.

## Complexity Tracking

> No Constitution Check violations. All gates pass.
