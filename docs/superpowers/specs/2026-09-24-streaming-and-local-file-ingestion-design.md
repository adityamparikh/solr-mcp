# Spec: streaming `index-url` and indexing local files

**Date:** 2026-09-24
**Status:** proposed — records the agreed approach; nothing here is implemented yet
**Builds on:** [`2026-09-15-url-ingestion-design.md`](2026-09-15-url-ingestion-design.md)
(`index-url`, PR #210, issue #208), whose §10 this spec replaces.

Two questions, one answer each:

1. **Remote files** (public web, GitHub, S3 via presigned URL): how can `index-url`
   drop its 10 MB limit and keep a simple interface? *By streaming the download into
   Solr instead of holding it in the server's memory* (§3).
2. **Files on the user's machine**: how can they be indexed without passing through
   the model, in both transports? *Today, by Solr's own tooling run on that machine;
   later, by MCP file upload (SEP-2631). Elicitation was considered and is not the
   route* (§4).

---

## 1. Where the data is, and what reaches it

The MCP transport (STDIO or HTTP) only carries the tool call and its reply. The file
itself never crosses it, so what matters is what the **server** can reach:

```
 STDIO:  [ user's machine:  AI client ── solr-mcp ── ~/data.csv ]        same machine
 HTTP:   [ user's machine:  AI client, ~/data.csv ] ──net──► [ solr-mcp ]  server elsewhere
```

| Where the file is | Server can reach it? | Route |
|---|---|---|
| Public web / GitHub | yes, over HTTP | `index-url`, streamed (§3) |
| S3 / object storage | yes, with a presigned URL on the allow-list | `index-url`, streamed (§3) |
| User's machine, STDIO | yes (same disk) — but see §4.1 | Solr tooling now; SEP-2631 later (§4) |
| User's machine, HTTP | no | Solr tooling now; SEP-2631 later (§4) |
| Small pasted data | arrives in the tool call | the inline `index-*-documents` tools, unchanged |

Rule carried over from PR #194: **no transport-only tools.** Every tool is registered
identically in STDIO and HTTP, so each route above must work in both.

---

## 2. Today (PR #210)

```
 source ──bytes──► [ server memory: whole body, ≤ 10 MB ] ──► indexPayload ──► Solr
                    read to the last byte, then index
```

The body is read into memory, then indexed as the inline tool for its format would
index it (CSV/XML forwarded to Solr's update handlers, JSON/Markdown parsed by the
server). The 10 MB cap (`SOLR_INDEX_URL_MAX_BYTES`) exists only to bound that memory,
and it is why the tool, its description and the `index-data` prompt all have to
explain a limit and a fallback.

---

## 3. Remote files: stream the download into Solr

```
 source ──chunk──► server ──chunk──► Solr /update
        ──chunk──► (copies   ──chunk──► (parses and indexes
        ──chunk──►  ~8 KB at ──chunk──►  as bytes arrive)
                     a time)
```

### 3.1 Decisions

**S1. JSON, CSV and XML stream to Solr; nothing is held in memory.**
The fetched response stream becomes the single content stream of a
`ContentStreamUpdateRequest`, with the Content-Type for the format. Server memory per
call is a fixed small buffer regardless of file size, so **the size cap and
`SOLR_INDEX_URL_MAX_BYTES` are removed** for these formats, along with the
"over the limit, use `bin/solr post`" error and guidance.

*Verified against SolrJ 10.0.0 source:* `HttpJdkSolrClient.preparePutOrPost` sends a
request's content writer through a `PipedOutputStream`/`PipedInputStream` into
`HttpRequest.BodyPublishers.ofInputStream`, and
`ContentStreamUpdateRequest.getContentWriter` copies its one stream with
`transferTo`. `XMLRequestWriter` (which `SolrConfig` installs) only substitutes its own
writer for `UpdateRequest`; `ContentStreamUpdateRequest` is an
`AbstractUpdateRequest`, so it takes the streaming path. The body is streamed, not
buffered.

**S2. Formats map to Solr's own handlers.**

| Format | Solr endpoint | Content-Type | Note |
|---|---|---|---|
| CSV | `/update` + `header=true` | `text/csv; charset=UTF-8` | as `index-csv-documents` today |
| XML | `/update` | `application/xml` | must be an `<add>` block (S3) |
| JSON | `/update/json/docs` | `application/json` | one document per object; see S4 |
| Markdown | not streamed | — | parsed by the server (S5) |

**S3. The XML `<add>` guard stays, on a peeked prefix.** `SolrUpdateXml` rejects
anything but an `<add>` root, because the same grammar carries `<delete>` and
`<commit>`. It reads only up to the root element, so the fetcher wraps the response in
a `BufferedInputStream`, marks it, lets the StAX check read the prolog and root, and
resets before streaming. The mark limit (e.g. 64 KB) bounds a pathological prolog; a
prolog longer than that is rejected as not an `<add>` block.

**S4. JSON changes behaviour, deliberately.** Streaming JSON means Solr, not the
server, turns objects into documents, so the server's nested-object flattening no
longer applies to `index-url` JSON (field-name sanitising is being removed anyway in
PR #235). This is the same trade #205 made for CSV and XML. Use
`/update/json/docs` rather than `/update`: on `/update`, a nested map such as
`{"set": "x"}` is read as an atomic-update instruction, which is not what a user
indexing a data file means. **To verify with a test before implementing:** how
`/update/json/docs` with default parameters treats nested objects and arrays of
objects on the Solr versions in the compatibility matrix (8.11, 9.x, 10).

**S5. Markdown keeps a fixed internal cap.** Solr cannot parse Markdown, so the server
must read the whole document. Keep a bound, but make it an internal constant (10 MB),
not an operator property: a single Markdown document of that size is already far
beyond what this tool is for. The error says so and names the size.

**S6. A partial transfer must never be reported, or committed, as success.**
This is the main correctness risk, found while verifying S1: when the content writer
fails, `HttpJdkSolrClient` only logs `Cannot write Content Stream` and closes the pipe.
Solr then sees a shorter body that may still be valid (a truncated CSV is a valid
CSV), indexes it and returns 200. So:

- **The commit does not ride on the streaming request.** PR #210 (through the inline
  tools) sets `commit`/`softCommit` on the update request itself. For streaming, send
  the update without a commit, then send a separate commit **only if** the transfer
  completed.
- **The server decides "completed", not Solr.** The fetched stream is wrapped in a
  counting stream that records EOF, any read exception, and the timeout; if the
  source sent a `Content-Length`, the byte count must match it. Any failure → no
  commit, and the tool reports an error.
- **What the error must say.** Documents Solr already added from the partial body are
  not visible yet (no commit), but the `_default` configset's `autoCommit` (15 s,
  `openSearcher=false`) makes them durable, and the next commit by anyone makes them
  visible. The message therefore says the transfer failed partway, that some
  documents may appear in the collection, and to re-run (IDs make re-indexing
  idempotent) or delete by query.

**S7. Everything that protects the fetch stays.** Allow-list, link-local and
cloud-metadata refusal on every hop, no credentials or caller headers, redirect limit,
non-2xx rejected before any body is read, format resolution, connect/read/total
timeouts. The concurrency limit (`SOLR_INDEX_URL_MAX_CONCURRENT_FETCHES`, default 4)
stays: a stream holds an outbound connection and a Solr connection for its whole
duration even though it no longer holds memory. The total timeout (default 5 m)
becomes the practical bound on file size; its message should say so.

**S8. Transport.** Identical in STDIO and HTTP; nothing in this section is
transport-specific.

### 3.2 Interface after the change

```
index-url(collection, url, format?)
```

- Same three arguments. No size limit to explain for JSON/CSV/XML.
- Reply: CSV/XML/JSON → Solr's acceptance plus the byte count transferred (Solr's
  update response carries no document count; the `index-data` prompt already verifies
  the count in its next step). Markdown → the document count, as today.
- Configuration removed: `SOLR_INDEX_URL_MAX_BYTES`. Kept: allowed hosts, the three
  timeouts, the concurrency limit.

### 3.3 Tests to add

- A body larger than the old cap streams through with constant server memory (assert
  on documents indexed, not on heap).
- Source connection dropped mid-body, with and without `Content-Length`: error
  returned, no commit sent, and the partial-transfer wording.
- Total timeout hit mid-stream: same as above.
- XML with a `<delete>` root is refused before any byte reaches Solr; an `<add>`
  document with a long comment prolog under the mark limit is accepted.
- JSON with nested objects, run against every image in the Solr compatibility matrix
  (S4 verification).

---

## 4. Files on the user's machine

### 4.1 Options considered

**A. Read a local path on the server (`index-file(path)`).** Works only when the
server shares the user's disk, i.e. STDIO. Rejected: it was PR #194, closed on
2026-09-12 as a transport-only tool.

**B. Form-mode elicitation.** The server asks the client to show the user a form.
Form schemas are limited to flat objects of primitive properties (string, number,
integer, boolean, enum); there is no file or binary type
([MCP spec, elicitation](https://modelcontextprotocol.io/specification/draft/client/elicitation)).
The most a form can collect is a path string, which is option A again. **Not viable.**

**C. URL-mode elicitation (MCP `2025-11-25`).** The server asks the client to open a
URL in the user's browser; the interaction there is out of band, and the client and
model never see it. The server could serve an upload page, and the browser would post
the file straight to the server, which streams it into Solr with the §3 path:

```
 AI client ──tools/call index-file(collection)──► server
           ◄─ elicitation/create {mode:url, url:https://server/upload/<id>} ─
 user's browser ──(opens URL, picks file, POST)──► server ──stream──► Solr
 AI client ──retry with requestState──► server ──► "Solr accepted …"
```

It keeps the file out of the model and works for HTTP. It is **not the chosen route**,
because:

1. **No STDIO equivalent.** A STDIO server has no network listener, and opening one
   is a `VALID` finding under the threat model (§13, breaking a §8 property), so it
   cannot serve an upload page. STDIO would need a different
   mechanism, which makes this transport-specific again.
2. **HTTP mode is stateless today.** `application-http.properties` sets
   `spring.ai.mcp.server.protocol=stateless`, which disables elicitation, and
   correlating the browser upload with the tool call needs server-side state. It
   would require moving the HTTP transport to stateful Streamable HTTP.
3. **New authenticated web surface.** The spec requires the server to verify that the
   user who opens the URL is the user who triggered the elicitation (its phishing
   section: typically a browser session tied to the same authorization server, with
   matching `sub`). The server has no browser login today; this is a new endpoint,
   session handling and threat-model section.
4. **Uneven client support.** URL mode is marked new in `2025-11-25` and "may change";
   clients must declare `elicitation.url`, and Spring AI 1.1.7 support for URL mode is
   unverified.

**D. MCP file upload, SEP-2631 "File Objects and Transfer"** (open draft,
modelcontextprotocol PR #2631; it replaced SEP-2356, closed 2026-06-26). The client
asks the server for an upload slot (`files/authorizeUpload`), uploads the file to the
URL it is given, and passes a file reference to the tool. It is one protocol-level
mechanism for both transports, implemented by the client rather than a web page we
host, and it is where the MCP ecosystem is converging.

**E. Solr's own tooling, run on the user's machine.** `bin/solr post -c <collection>
<file>`, or `curl` to `/update`. Streams, has no size limit, and works whichever
transport the MCP server uses. Its cost is a manual step, and the user's machine must
be able to reach Solr.

### 4.2 Decision

- **Now:** E. The `index-data` prompt already tells the model to give the user the
  `bin/solr post` / `curl` command for local or very large files (PR #210). Keep it.
- **When SEP-2631 is accepted and supported by Spring AI and at least one major
  client:** add `index-file(collection, file, format?)` taking a SEP-2631 file
  reference, registered in both transports, reusing the §3 streaming path unchanged
  (a file handle is just another input stream).
- **Revisit C** only if SEP-2631 stalls **and** the HTTP transport has moved to
  stateful mode for other reasons; even then, STDIO needs its own answer.

---

## 5. Phases

| Phase | Scope | Depends on |
|---|---|---|
| A | §3: stream JSON/CSV/XML in `index-url`, remove `SOLR_INDEX_URL_MAX_BYTES`, fixed Markdown cap, partial-transfer handling | PR #210; S4 verification |
| B | §4.2: `index-file` via SEP-2631, both transports, reusing phase A | SEP-2631 accepted; Spring AI support |

Phase A can land in PR #210 itself or as a follow-up PR; it changes no tool signature.

## 6. Open questions

1. S4: exact `/update/json/docs` handling of nested objects across Solr 8.11, 9.x, 10.
2. S6: whether to report the byte count, or nothing, on success for streamed formats.
3. S3: the mark limit for the XML prolog peek (proposed 64 KB).
4. Whether the concurrency limit and total timeout defaults (4, 5 m) still fit once
   the size cap is gone.
