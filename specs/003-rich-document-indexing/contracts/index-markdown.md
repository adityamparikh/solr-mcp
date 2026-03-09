# Contract: index-markdown Tool (New in 2.0.0)

**Feature**: 003-rich-document-indexing
**Tool name**: `index-markdown`
**MCP annotation**: `@McpTool(name = "index-markdown", ...)`
**New tool** — no pre-existing signature

**No external dependencies**: This tool does NOT require Docling Serve.

---

## Parameter Schema

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to index the document into |
| `markdown` | `String` | Yes | — | Markdown string to index. May optionally begin with YAML frontmatter (between `---` delimiters). |
| `id` | `String` | No | UUID | Unique document identifier. Auto-generated UUID if not provided. |

---

## Response Schema

```json
{
  "id": "<id or generated uuid>",
  "frontmatterFields": ["<field1>", "<field2>"],
  "skippedFields": ["<reserved_field>"],
  "message": "Markdown document indexed successfully."
}
```

| Field | Description |
|-------|-------------|
| `id` | The `id` field value stored in Solr |
| `frontmatterFields` | Names of frontmatter keys successfully stored as Solr fields |
| `skippedFields` | Names of frontmatter keys skipped (reserved Solr names) |
| `message` | Human-readable summary |

---

## Validation Rules

1. If `markdown` is null or empty: return validation error.
2. If `id` is not provided: generate a UUID.
3. If a frontmatter key matches a reserved Solr field (`id`, `_version_`, `_root_`): skip it; include it in `skippedFields` in the response.
4. If frontmatter YAML is malformed: skip frontmatter parsing entirely; index document with `content` = full markdown string; include warning in `message`.

---

## Behavioral Contract

1. Receive `markdown` string (and optional `id`)
2. Detect YAML frontmatter: check for `---\n` at start
3. If frontmatter present: extract YAML block; parse scalar key-value pairs via SnakeYAML
4. Build `SolrInputDocument`:
   - `id` = provided or generated UUID
   - `content` = markdown body (frontmatter stripped)
   - `source` = "direct"
   - `format` = "text/markdown"
   - `<frontmatter_key>` = `<value>` for each non-reserved key (sanitized via `FieldNameSanitizer`)
5. Call `indexDocuments(collection, List.of(doc))`
6. Return `MarkdownIndexingResult`

---

## Example: Markdown with Frontmatter

**Input**:
```markdown
---
title: Quarterly Report Q1
author: Jane Smith
date: 2026-03-01
category: Finance
---

## Executive Summary

Revenue increased 12% year-over-year...
```

**Resulting Solr document**:
```json
{
  "id": "<uuid>",
  "content": "## Executive Summary\n\nRevenue increased 12% year-over-year...",
  "source": "direct",
  "format": "text/markdown",
  "title": "Quarterly Report Q1",
  "author": "Jane Smith",
  "date": "2026-03-01",
  "category": "Finance"
}
```

---

## Java Method Signature

```java
@McpTool(name = "index-markdown", description = "...")
@PreAuthorize("isAuthenticated()")
@Observed
public MarkdownIndexingResult indexMarkdown(
    @McpToolParam(description = "Solr collection to index into") String collection,
    @McpToolParam(description = "Markdown string to index; may include YAML frontmatter") String markdown,
    @McpToolParam(description = "Optional document ID; UUID generated if absent") @Nullable String id
)
```

---

## Authentication

`@PreAuthorize("isAuthenticated()")` — same as all existing indexing tools.
