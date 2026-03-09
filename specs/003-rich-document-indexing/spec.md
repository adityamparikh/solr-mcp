# Feature Specification: Rich Document Indexing

**Feature Branch**: `003-rich-document-indexing`
**Created**: 2026-03-08
**Status**: Draft
**Input**: User request — expand indexing to support rich document formats (PDF, DOCX, PPTX, XLSX, HTML, images) via docling-java, with markdown export; plus direct markdown indexing

## Clarifications

### Session 2026-03-08

- Q: Should all document formats be supported in a single new MCP tool or multiple tools? → A: Single new tool `index-rich-document` accepting either a URL or a local file path. URL works in all deployment modes (STDIO + HTTP); local file path works in STDIO mode when the server and client share the same filesystem. Both are optional inputs; at least one must be provided.
- Q: Should markdown indexing be a separate tool or combined with the rich document tool? → A: Separate tool `index-markdown` — it requires no Docling Serve backend, so it should be independently usable. Follows the existing pattern of one tool per input format in `IndexingService`.
- Q: What Solr document structure should converted documents produce? → A: Each rich document becomes a single Solr document with: `id` (auto-generated UUID), `content` (full markdown text), `source` (original URL or file path), `format` (detected MIME type), and any frontmatter key-value pairs extracted from the converted markdown.
- Q: How is Docling Serve distributed and operated alongside the MCP server across all deployment modes (STDIO/HTTP × source/JAR/Docker image)? → A: For source and JAR modes (any transport), Docling Serve runs as a separate local Docker container on the same host; `DOCLING_SERVE_URL=http://localhost:5001`. For Docker image mode (any transport), both containers must share a Docker network; the project MUST ship a `docker-compose.yml` that starts both services together; `DOCLING_SERVE_URL=http://docling:5001`. This answer is superseded — see the docling4j clarification below.
- Q: Should the implementation use `docling4j` (embeds Python via GraalPy — no external Docker container) or `docling-java` (HTTP client to external Docling Serve Docker container)? → A: **docling4j** (`com.ibm.docling:docling4j:0.1.1`) is preferred for JVM deployments because it eliminates the external Docker dependency. GraalPy itself can run in GraalVM native image mode, but the Python ML libraries that power docling (PyTorch, ONNX, OCR engines) cannot be statically compiled or bundled into a native executable. Therefore: (1) JVM deployments (STDIO/HTTP from source, JAR, or JVM Docker image) use `docling4j` — single-deployment-unit, no sidecar required; (2) Native image deployments (feature 001) cannot bundle the ML dependencies — `index-rich-document` is unavailable in native mode and returns a clear error. `index-markdown` is unaffected. See Constraints & Tradeoffs for full details.
- Q: Would Apache Tika be a simpler alternative to docling4j, and does docling-produced markdown differ meaningfully from Tika-extracted text for lexical or vector similarity search? → A: For DOCX, HTML, and PPTX, Tika quality is comparable. For complex multi-column PDFs (the P1 use case — research papers), docling's ML-based layout analysis produces meaningfully better output: it preserves reading order across columns, correctly groups table content, and integrates OCR coherently. Tika's rule-based extraction linearises multi-column layouts unpredictably, producing out-of-order token sequences that degrade both BM25 lexical relevance (scattered sentence fragments score poorly) and vector embedding quality (incoherent text sequences produce semantically diluted embeddings). docling4j is retained. Apache Tika is documented as a future fallback option in Constraints & Tradeoffs.

### Session 2026-03-09

- Q: [OPEN] When `index-rich-document` is called with a URL or file path pointing to a `.md` file, what should happen?
  - **Option A** — Return a clear error: "Use `index-markdown` for markdown content." Keeps tool responsibilities strictly separated; caller must choose the right tool.
  - **Option B (recommended)** — Auto-detect `.md`/`text/markdown` and route directly to `MarkdownDocumentCreator`, bypassing docling4j entirely. Transparent to the caller; markdown is plain text and needs no ML conversion.
  - **Option C** — Route through docling4j (Markdown is a supported InputFormat). Consistent with other formats but incurs unnecessary GraalPy startup overhead for plain text.

- Q: [OPEN] In what format should the `content` field store the markdown content in Solr?
  - **Option A (recommended)** — Store **raw markdown** (with syntax) in `content` as a `text_general` field. Solr's standard tokenizer strips `#`, `*`, `[]()` as punctuation during analysis — lexical search works correctly without pre-processing. For feature 002 embedding generation, the application strips markdown syntax before calling the embedding model (separate concern).
  - **Option B** — Strip markdown to **plain text** before indexing into `content`. Simpler for embeddings; returned documents contain plain text, not the original markdown.
  - **Option C** — Store raw markdown in `content_raw` (stored, not indexed) and stripped plain text in `content` (indexed). Best retrieval fidelity and clean search; doubles storage and adds schema complexity.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Index a Rich Document via URL (Priority: P1) 🎯 MVP

An AI assistant retrieves a research paper PDF hosted on the web. The user says "index this paper for me" and provides the URL. The AI calls `index-rich-document` with the URL. The server sends the URL to Docling Serve, which converts the PDF to markdown. The server indexes the markdown as a Solr document. The document is then immediately discoverable via the existing `search` tool.

**Why this priority**: This is the primary end-user value proposition — rich documents are the most common format in real knowledge-base workflows. URL-based sourcing works across all deployment modes.

**Independent Test**: Call `index-rich-document` with a publicly accessible PDF URL and Docling Serve configured; then call `search` with a keyword from the PDF; verify the document appears in results.

**Acceptance Scenarios**:

1. **Given** Docling Serve is configured and running, **When** `index-rich-document` is called with a valid URL pointing to a PDF, **Then** the document is converted to markdown and indexed in Solr with fields `id`, `content`, `source`, and `format`.
2. **Given** `index-rich-document` is called with a DOCX, PPTX, XLSX, or HTML URL, **Then** that format is also successfully converted and indexed.
3. **Given** the document is indexed, **When** `search` is called with keywords from the document content, **Then** the document appears in results.
4. **Given** `index-rich-document` is called in native image mode (where ML dependencies are unavailable), **Then** a clear error is returned: "Rich document indexing requires JVM mode. This feature is unavailable in native image builds."
5. **Given** the URL points to a format Docling does not support, **Then** a clear error is returned describing the unsupported format.
6. **Given** `index-rich-document` is called with neither a URL nor a file path, **Then** a validation error is returned.

---

### User Story 2 — Unchanged Behavior Without Docling Configured (Priority: P1)

An existing operator upgrades the MCP server. All existing `index-json-documents`, `index-csv-documents`, `index-xml-documents`, and `search` calls behave identically to before. `index-rich-document` is available in JVM mode (docling4j runs in-process). `index-markdown` works normally in all modes.

**Why this priority**: Operators who don't need rich document indexing must experience zero behavioral change and zero startup errors.

**Independent Test**: Start the server in JVM mode; verify all existing tool calls succeed; verify `index-rich-document` returns a clear error in native image mode; verify `index-markdown` succeeds in all modes.

**Acceptance Scenarios**:

1. **Given** the server starts in JVM mode, **Then** it starts successfully with no errors, all existing tools are available, and `index-rich-document` is available.
2. **Given** the server starts in native image mode, **When** all existing `index-*` and `search` tool calls are made, **Then** they behave identically to pre-2.0.0.
3. **Given** the server runs in native image mode, **When** `index-rich-document` is called, **Then** a clear, human-readable error is returned (no stack trace).
4. **Given** any deployment mode, **When** `index-markdown` is called, **Then** it succeeds — markdown indexing has no Python/ML dependency.

---

### User Story 3 — Index a Markdown Document Directly (Priority: P2)

An AI assistant receives a markdown document (a README, report, or notes file). The user says "index this markdown." The AI calls `index-markdown` with the markdown string. The server creates a Solr document from it. No Docling Serve is involved.

**Why this priority**: Markdown is a common interchange format. Many users already have content in this format; requiring Docling for it would be unnecessary overhead.

**Independent Test**: Call `index-markdown` with a markdown string (including frontmatter) against Solr; verify the document is indexed with frontmatter fields; verify searchable with no Docling configured.

**Acceptance Scenarios**:

1. **Given** `index-markdown` is called with a markdown string, **Then** a single Solr document is created with `content` containing the full markdown and indexed.
2. **Given** the markdown contains YAML frontmatter (`---` delimiters at start), **Then** each frontmatter key-value pair is stored as an additional Solr field alongside `content`.
3. **Given** `index-markdown` is called without Docling Serve configured, **Then** indexing succeeds.
4. **Given** the indexed markdown document, **When** `search` is called with a keyword from the content, **Then** the document appears in results.

---

### User Story 4 — Index a Local File by Path (Priority: P3)

An AI assistant running in STDIO mode (Claude Desktop) needs to index a local PDF file the user is working with. The user provides the file path. The AI calls `index-rich-document` with `filePath`. The server reads and sends the file to Docling Serve.

**Why this priority**: Local file access is specific to STDIO/co-located deployments; important for Claude Desktop users but not applicable to remote HTTP deployments.

**Independent Test**: Call `index-rich-document` with a valid local file path (test PDF) and Docling Serve configured; verify the document is indexed.

**Acceptance Scenarios**:

1. **Given** `index-rich-document` is called with a local file path and Docling Serve configured, **Then** the file is read, encoded, sent to Docling Serve, and the converted markdown is indexed.
2. **Given** the local file path does not exist, **Then** a clear error is returned: "File not found or not readable: `<path>`".
3. **Given** the file exceeds the configured size limit (default: 50 MB), **Then** a clear error is returned before sending to Docling.
4. **Given** both `fileUrl` and `filePath` are supplied, **Then** `fileUrl` takes precedence.

---

### Edge Cases

- What happens when Docling Serve returns an error mid-conversion? Return a clear error with the Docling error message; no partial indexing.
- What happens when converted markdown is empty (document had no extractable text)? Index the document with empty `content`; response notes that no extractable text was found.
- What happens when Docling Serve times out? Return a clear timeout error rather than hanging; configurable timeout (default 120s).
- What happens when `filePath` is provided in HTTP/remote deployment mode? Return a clear error explaining that `filePath` requires a locally accessible path (STDIO mode recommended).
- What happens when the markdown has no frontmatter? The document is indexed with only `id`, `content`, and `source` — no error.
- What happens when a frontmatter key conflicts with a reserved Solr field name (`id`, `_version_`)? That key is skipped; the other frontmatter fields are stored normally; the outcome is reported.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The server MUST expose a new `index-rich-document` MCP tool. Parameters: `collection` (required), `fileUrl` (optional URL string), `filePath` (optional local file path string). At least one of `fileUrl` or `filePath` must be provided; a validation error is returned if both are absent. When both are provided, `fileUrl` takes precedence.
- **FR-002**: When `index-rich-document` is called with `fileUrl`, the server MUST send the URL to Docling Serve via an `HttpSource` request and index the returned markdown.
- **FR-003**: When `index-rich-document` is called with `filePath`, the server MUST read the file from the local filesystem (up to the configured size limit), encode it as base64, and send it to Docling Serve via a `FileSource` request.
- **FR-004**: The server MUST expose a new `index-markdown` MCP tool. Parameters: `collection` (required), `markdown` (required string), `id` (optional string; UUID generated if absent). This tool MUST NOT require Docling Serve.
- **FR-005**: `index-markdown` MUST parse YAML frontmatter (content between `---` delimiters at the start of the markdown) and store each key-value pair as a Solr field. Reserved Solr field names (`id`, `_version_`) MUST be skipped with a warning; all others are stored.
- **FR-006**: Both `index-rich-document` and `index-markdown` MUST produce Solr documents with at minimum: `id`, `content` (full markdown text), `source` (origin), `format` (MIME type string).
- **FR-007**: `index-rich-document` MUST detect at startup whether the docling4j GraalPy context can be initialized. When ML dependencies are absent (native image mode), `DoclingService.isConfigured()` MUST return `false`; calls to `index-rich-document` MUST return a clear error. `index-markdown` MUST be unaffected.
- **FR-008**: The server MUST start and all pre-existing tools MUST function correctly in both JVM and native image modes. No external service URL is required.
- **FR-009**: `index-rich-document` MUST support at minimum: PDF, DOCX, PPTX, XLSX, HTML, PNG, JPEG, TIFF (all formats supported by the docling4j in-process engine).
- **FR-010**: The docling4j conversion operation MUST have a configurable timeout (default: 120 seconds; configurable via `DOCLING_SERVE_TIMEOUT_SECONDS`). Timeouts MUST produce a clear error.
- **FR-011**: Local file uploads via `filePath` MUST enforce a configurable maximum size (default: 50 MB; configurable via `DOCLING_MAX_FILE_SIZE_MB`). Files exceeding the limit MUST return a clear error before processing.
- **FR-012**: Both new tools MUST carry `@PreAuthorize("isAuthenticated()")` — same authentication as existing tools in HTTP mode.
- **FR-013**: All configuration properties (`DOCLING_SERVE_TIMEOUT_SECONDS`, `DOCLING_MAX_FILE_SIZE_MB`) MUST have documented defaults and MUST NOT affect server startup when absent. No external service URL configuration is required.

### Key Entities

- **RichDocument**: A document sourced from a URL or local file path, converted to markdown by Docling Serve. Stored in Solr as a single document.
- **MarkdownDocument**: A document provided directly as a markdown string. Parsed for frontmatter; stored in Solr as a single document.
- **Docling Serve**: External Python-based document conversion service (`quay.io/docling-project/docling-serve`). The MCP server interacts with it via `ai.docling:docling-serve-client`. Optional at startup; required only for `index-rich-document`.
- **Frontmatter**: YAML key-value pairs between `---` delimiters at the start of a markdown document. Each pair becomes a Solr field on the indexed document.

## Success Criteria *(mandatory)*

- **SC-001**: A PDF at a public URL is indexed via `index-rich-document` and found via `search` in a single test session with no manual steps.
- **SC-002**: Each supported format (PDF, DOCX, PPTX, XLSX, HTML) is successfully indexed in integration tests using sample files.
- **SC-003**: `index-markdown` indexes a frontmatter-containing markdown document with all frontmatter fields appearing as Solr fields, verified by searching on a frontmatter field value.
- **SC-004**: All existing tool calls produce identical responses with no Docling Serve configured — zero behavioral regression.
- **SC-005**: Calling `index-rich-document` without Docling Serve configured produces a human-readable error (no stack trace).
- **SC-006**: Both new tools enforce authentication in HTTP mode — calls without credentials are rejected.
- **SC-007**: New tools are tested against all supported Solr versions; Solr version does not affect conversion (conversion is Docling-side); Solr indexing step works on 8.11, 9.4, 9.9, 9.10, 10.

## Constraints & Tradeoffs

### docling4j: In-Process Python via GraalPy (Selected)

`docling4j` (`com.ibm.docling:docling4j:0.1.1`) embeds the Python Docling engine directly in the JVM using GraalPy (Oracle's embeddable Python 3 runtime). Document processing runs **in-process** — no external Docker container or sidecar required. This is the correct choice for an MCP server that should "just work" without extra operator infrastructure.

**Deployment table — all 6 modes with docling4j:**

- **STDIO from source**: `./gradlew bootRun` — rich document indexing works, GraalPy starts on first call
- **STDIO from JAR**: `java -jar solr-mcp.jar` — same; GraalPy included in the JAR
- **STDIO from Docker image**: `docker run solr-mcp-image` — same; GraalPy in the image
- **HTTP from source**: `PROFILES=http ./gradlew bootRun` — same
- **HTTP from JAR**: `PROFILES=http java -jar solr-mcp.jar` — same
- **HTTP from Docker image**: `docker run` — same; no `docker-compose.yml` required for Docling

**Native image (feature 001) — known limitation**: GraalPy itself can run in GraalVM native image mode via Truffle's Substrate VM support. However, the docling Python engine's ML dependencies (PyTorch, ONNX runtimes, OCR engines) are large native C++ libraries that cannot be statically compiled into a native executable. The `index-rich-document` feature is therefore **unavailable** in native image mode. `DoclingService.isConfigured()` returns `false` in native mode; callers receive a clear error. `index-markdown` is unaffected — it has no Python dependency.

**Why not docling-java (external Docling Serve)?**
Requires operators to provision `ghcr.io/docling-project/docling-serve:v1.13.0` as a separate Docker container. Acceptable for microservice architectures, but adds operational burden for the typical MCP server deployment. Kept as an implementation note — can be adopted instead if GraalPy overhead proves prohibitive in practice.

**Why not Apache Tika?**
Apache Tika (`org.apache.tika:tika-parsers-standard-package`) is a mature, pure-Java alternative that works in both JVM and native image mode. For DOCX, HTML, and PPTX documents, Tika quality is comparable to docling4j. However, for complex multi-column PDFs — the P1 use case (research papers) — Tika's rule-based extraction produces meaningfully worse output:

- **Lexical search**: Multi-column PDF layouts are linearised unpredictably; sentences from adjacent columns are interleaved, producing low-coherence token sequences that reduce BM25 relevance scores.
- **Vector/semantic search**: Incoherent reading-order text produces semantically diluted embeddings; chunks containing sentence fragments from multiple topics cluster poorly.

docling4j's ML-based layout analysis correctly identifies columns, preserves reading order, and integrates OCR coherently. The search quality difference is most pronounced for the documents that most benefit from rich indexing. **Tika remains a viable fallback** if docling4j v0.1.1 API proves unstable or if native image compatibility is later required for this feature.

### Tool Surface Decision

**Option A — Rejected**: Extend existing `index-json-documents` with a `sourceUrl` parameter. Rejected: JSON format parsing and rich document conversion are fundamentally different input modalities with incompatible parameters. Conflating them would make the tool confusing and hard to test independently.

**Option B ✅ — Selected**: Two new tools — `index-rich-document` (Docling-backed) and `index-markdown` (lightweight, no Docling). Consistent with the existing one-tool-per-format convention in `IndexingService`. `index-markdown` adds a fourth `SolrDocumentCreator` implementation (JSON + CSV + XML + Markdown = 4 implementations), satisfying Constitution Principle V (abstraction justified by 3+ callers). `index-rich-document` uses the Docling conversion pipeline rather than the `SolrDocumentCreator` strategy — its output (markdown) is then passed through the `MarkdownDocumentCreator`.

### Binary File Transfer Constraints

Large binary files cannot be reliably passed as MCP tool parameter strings. Therefore URL-based sourcing (`fileUrl`) is the recommended and primary path. File path sourcing (`filePath`) is a practical alternative for STDIO/co-located deployments. Base64 encoding of files is handled server-side (never exposed in the MCP interface).

## Assumptions

- Operators running `index-rich-document` provision and operate Docling Serve independently. The MCP server does not start, stop, or monitor the Docling Serve container lifecycle.
- Docling Serve is configured once at startup. Runtime reconfiguration requires server restart.
- Each rich document produces exactly one Solr document. Document chunking (splitting large docs into multiple Solr records) is out of scope.
- Solr version compatibility is identical to existing tools — conversion happens before Solr interaction.
- YAML frontmatter parsing covers scalar values (string, number, boolean). Nested YAML objects are stored as their string representation.
- Image-based document processing (PNG, JPEG, TIFF) requires OCR capability in the Docling Serve deployment; the MCP server does not configure OCR settings.
