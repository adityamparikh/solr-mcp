# Phase 0 Research: Rich Document Indexing

**Feature**: 003-rich-document-indexing
**Date**: 2026-03-08
**Status**: Complete

---

## 1. Library Selection: docling4j (Not docling-java)

### Decision
Use `com.ibm.docling:docling4j:0.1.1` (IBM's docling4j). Inject `DoclingService` as an optional Spring bean.

### Key Findings

**Two separate Java libraries exist for docling:**

- **`ai.docling:docling-serve-client`** — Java HTTP client for Docling Serve (external Python Docker container required). Originally researched first.
- **`com.ibm.docling:docling4j`** — Embeds the Python docling engine **in-process** using GraalPy (Oracle's embeddable Python 3 runtime for the JVM). **No external Docker container required.**

**docling4j is selected** because it eliminates the external Docker dependency. Operators get rich document indexing by simply including the JAR — no sidecar, no docker-compose, no network configuration.

### Native Image Limitation
GraalPy can theoretically run in GraalVM native image mode via Truffle Substrate VM support. However, the docling Python engine depends on native ML libraries (PyTorch, ONNX runtimes, OCR engines — large C++ binaries) that cannot be statically compiled into a native executable. Therefore, `index-rich-document` is unavailable in native image builds (feature 001). `DoclingService.isConfigured()` returns `false` at runtime when native image mode is detected; callers receive a clear error. `index-markdown` is unaffected.

### Maven Artifacts (Maven Central)

| Artifact | Version | Purpose |
|----------|---------|---------|
| `com.ibm.docling:docling4j` | 0.1.1 | In-process docling engine via GraalPy; no external Docker required |

**For reference (not used in this implementation):** `ai.docling:docling-serve-client:0.4.7` is the HTTP client for Docling Serve (external Docker sidecar). Can be adopted as a fallback if GraalPy overhead proves prohibitive.

Gradle dependency addition (`libs.versions.toml`):
```toml
[versions]
docling4j = "0.1.1"

[libraries]
docling4j = { module = "com.ibm.docling:docling4j", version.ref = "docling4j" }
```

```kotlin
// build.gradle.kts
implementation(libs.docling4j)
```

---

## 2. Supported Input Formats

All formats below are supported by Docling Serve (handled server-side; MCP server passes the document through):

All 14 values of the `InputFormat` enum in docling-serve-api:

| InputFormat enum | Format |
|-----------------|--------|
| `PDF` | PDF documents |
| `DOCX` | Microsoft Word |
| `PPTX` | Microsoft PowerPoint |
| `XLSX` | Microsoft Excel |
| `HTML` | HTML pages |
| `IMAGE` | Images (PNG, TIFF, JPEG, etc.) |
| `AUDIO` | Audio (WAV, MP3) — requires ASR pipeline in Docling Serve |
| `CSV` | CSV files |
| `MARKDOWN` | Markdown (Docling can re-process existing markdown) |
| `ASCIIDOC` | AsciiDoc |
| `JSON_DOCLING` | Docling's own JSON format (re-ingestion) |
| `METS_GBS` | METS/Google Books format |
| `XML_JATS` | JATS XML (journal articles) |
| `XML_USPTO` | USPTO XML (patents) |

Image formats require OCR to be enabled in the Docling Serve deployment. Audio formats require a separate audio pipeline. For the initial implementation, the MCP server passes all formats through without format-specific validation — Docling Serve returns a clear error for unsupported types.

---

## 3. Supported Output Formats

Docling Serve can return:

| Format | Field on `DocumentResponse` |
|--------|-----------------------------|
| Markdown | `md_content` |
| HTML | `html_content` |
| Plain text | `text_content` |
| JSON (lossless) | `json_content` |
| DocTags | `doctags_content` |

**Decision**: Always request Markdown output. Markdown is human-readable, preserves document structure (headings, lists, tables), and integrates cleanly with Solr full-text search and semantic embedding generation (feature 002).

---

## 4. docling4j API

**⚠️ API verification required**: docling4j v0.1.1 is early-stage. The exact class/method names must be verified against the actual Maven artifact during implementation (Task T001). The patterns below are based on IBM's published documentation.

### Typical usage pattern

```java
// docling4j runs the Python docling engine in-process via GraalPy
// No configuration needed — works out of the box on JVM
DocumentConverter converter = new DocumentConverter();  // exact class TBD
ConversionResult result = converter.convert(Path.of("/tmp/report.pdf"));
String markdown = result.toMarkdown();  // or result.getMarkdown()
```

### DoclingService pattern (adapted for in-process library)

```java
@Service
public class DoclingService {
    private final boolean available;

    public DoclingService() {
        // Detect if docling4j GraalPy context can be initialized.
        // In native image mode, GraalPy ML dependencies are absent → UnsatisfiedLinkError or similar.
        boolean canInit = false;
        try {
            Class.forName("com.ibm.docling.DocumentConverter");  // or equivalent entry point
            canInit = true;
        } catch (ClassNotFoundException | UnsatisfiedLinkError | ExceptionInInitializerError e) {
            // docling4j absent (native image) or GraalPy ML libs not available
        }
        this.available = canInit;
    }

    public boolean isConfigured() { return available; }

    public String convertToMarkdown(Path filePath) {
        if (!available) throw new McpException(
            "Rich document indexing requires JVM mode. This feature is unavailable in native image builds.");
        // invoke docling4j API — exact method names TBD at T001
    }

    public String convertUrlToMarkdown(String url) {
        if (!available) throw new McpException("...");
        // download URL to temp file, pass to convertToMarkdown(Path)
    }
}
```

---

## 5. No External Configuration Required

Unlike docling-java (which needed `DOCLING_SERVE_URL`), docling4j runs in-process. No environment variables are needed for the document conversion engine. The only configuration remaining is the file size limit for `filePath` inputs:

```
DOCLING_MAX_FILE_SIZE_MB   → docling.max.file.size.mb (default: 50)
```

This maps to `application.properties`:
```properties
docling.max.file.size.mb=50
```

---

## 6. MarkdownDocumentCreator Design

Markdown is the fourth `SolrDocumentCreator` implementation. The input is a markdown string; the output is one `SolrInputDocument`.

**Frontmatter parsing**: YAML frontmatter is content between `---` delimiters at the start of the document. Each scalar key-value pair becomes a Solr field. Reserved Solr fields (`id`, `_version_`) are skipped with a log warning.

```java
public class MarkdownDocumentCreator implements SolrDocumentCreator {
    @Override
    public List<SolrInputDocument> create(String content) throws DocumentProcessingException {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", UUID.randomUUID().toString());
        // Parse frontmatter if present
        if (content.startsWith("---")) {
            // Extract YAML between first and second --- delimiters
            // Parse YAML scalars → add as Solr fields (skip reserved names)
            // Set content = remainder after frontmatter
        }
        doc.addField("content", content);
        doc.addField("format", "text/markdown");
        return List.of(doc);
    }
}
```

---

## 7. Integration Test Strategy

Since docling4j runs in-process (no external Docker container), integration tests for `index-rich-document` use the same pattern as other integration tests: real Solr via Testcontainers, mocked DoclingService (to avoid the GraalPy startup cost in CI), plus a separate `@Tag("slow")` test that exercises the real docling4j conversion pipeline.

```java
@Testcontainers
public class IndexingServiceRichDocIntegrationTest extends AbstractSolrIntegrationTest {
    // DoclingService: real in-process docling4j (no Docker sidecar needed)
    // Only real Solr container required for integration tests
}
```

No `docling-testcontainers` module is needed. The `ghcr.io/docling-project/docling-serve:v1.13.0` Docker image is NOT used in this implementation (it was required only for the docling-java HTTP client approach).

---

## 8. YAML Frontmatter Parsing

No heavy YAML library needed. Frontmatter is a narrow, well-defined format:
1. Document starts with `---\n`
2. YAML content follows
3. Closed by another `---\n` or `...\n`
4. Remainder is the markdown body

**Decision**: Use `org.yaml:snakeyaml` for YAML parsing. SnakeYAML is already a transitive dependency of Spring Boot; no new dependency needed.

---

## 9. Existing SolrDocumentCreator Interface Compatibility

The existing `SolrDocumentCreator.create(String content)` interface is sufficient for `MarkdownDocumentCreator` — markdown content is passed as a string. No interface change needed.

For `index-rich-document`, the flow is:
1. `IndexingService.indexRichDocument(...)` calls `DoclingService.convertUrlToMarkdown(url)` or `convertFileToMarkdown(...)`
2. Result (markdown string) is passed to `MarkdownDocumentCreator.create(markdown)`
3. Resulting `SolrInputDocument` is passed to existing `indexDocuments(collection, docs)`

This reuses the existing batch indexing pipeline and error handling. No new Solr-specific code needed.

---

## 10. Existing Tool Registration Test Impact

`McpToolRegistrationTest` uses reflection to verify `@McpTool` annotations. Adding `indexRichDocument` and `indexMarkdown` methods requires adding test assertions for the new methods. No existing test breaks — new assertions are additions only.

---

## Summary of Resolved Decisions

| Unknown | Resolution |
|---------|------------|
| docling library choice | `com.ibm.docling:docling4j:0.1.1` — in-process Python via GraalPy; **no external Docker container required** |
| Maven coordinates | `com.ibm.docling:docling4j:0.1.1` (single artifact; includes GraalPy runtime) |
| Output format | Always request Markdown (docling4j API returns markdown string) |
| Bean configuration | Plain `@Service`; `DoclingService.isConfigured()` checks GraalPy context initialization |
| Optional injection | `DoclingService.isConfigured()` returns `false` in native image mode (ML deps absent) |
| Frontmatter YAML | SnakeYAML (already in Spring Boot transitive deps) |
| New SolrDocumentCreator | `MarkdownDocumentCreator` — 4th implementation; existing interface unchanged |
| Integration tests | Real Solr via Testcontainers; docling4j runs in-process (no Docling Serve Docker needed) |
| Timeout | Configurable via `DOCLING_SERVE_TIMEOUT_SECONDS` (applies to GraalPy conversion; default 120s) |
| File size limit | Configurable via `DOCLING_MAX_FILE_SIZE_MB` (default 50 MB) |
