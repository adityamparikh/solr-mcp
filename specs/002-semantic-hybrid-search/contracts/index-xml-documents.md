# Contract: index-xml-documents Tool (Updated for 2.0.0)

**Feature**: 002-semantic-hybrid-search
**Tool name**: `index-xml-documents`
**MCP annotation**: `@McpTool(name = "index-xml-documents", ...)`
**Changed from**: pre-2.0.0 (2 parameters) → 2.0.0 (4 parameters, 2 new optional)

**See also**: `index-json-documents.md` — identical new-parameter semantics; input format is the only difference.

**Field name note**: XML element names become `SolrInputDocument` field names, with nested elements flattened using underscore notation (e.g., `<author><name>` → `author_name`) and attributes suffixed with `_attr` (e.g., `id` attribute → `id_attr`). `textFields` must reference the post-transformation field names.

---

## Parameter Schema

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `collection` | `String` | Yes | — | Solr collection to index documents into |
| `xml` | `String` | Yes | — | XML string; root element or child `doc`/`item`/`record` elements each become one document |
| `generateEmbeddings` | `Boolean` | No | `false` | When true, generates vector embeddings from `textFields` and stores them in each document |
| `textFields` | `List<String>` | No | `[]` | Post-transformation field names whose values are concatenated for embedding generation |

**Backward compatibility**: Both new parameters are optional with defaults that preserve pre-2.0.0 behavior.

---

## Response Schema

Same as `index-json-documents`:

```json
{
  "indexed": <int>,
  "skipped": <int>,
  "errors": ["<error message>", ...]
}
```

---

## Validation Rules

Same as `index-json-documents`. Key rule: `textFields` must reference the transformed element/attribute names as produced by `XmlDocumentCreator` (nested elements: `parent_child`; attributes: `name_attr`), not the raw XML element names.

---

## Java Method Signature (post-2.0.0)

```java
@McpTool(name = "index-xml-documents", description = "...")
@PreAuthorize("isAuthenticated()")
@Observed
public IndexingResponse indexXmlDocuments(
    String collection,
    String xml,
    Boolean generateEmbeddings,  // NEW — nullable; default false
    List<String> textFields      // NEW — nullable; default empty list
) throws ParserConfigurationException, SAXException, IOException, SolrServerException
```
