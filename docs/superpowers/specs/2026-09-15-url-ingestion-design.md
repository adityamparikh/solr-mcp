# Spec: `index-url` — stream documents into Solr from an http(s) URL

**Date:** 2026-09-15
**Status:** ready to implement
**Tracking issue:** https://github.com/apache/solr-mcp/issues/208
**Base:** branch `feat/file-ingest-required-params`, which already contains the
streaming spine and the `index-file` tool this spec builds on (§2).

This document is written for an implementer with no access to the discussions that
produced it. §3 lists every design decision with the reason it was taken; §4 to §7
are the specification; §8 is the definition of done. Three decisions in §3 were
closed by the spec author rather than the maintainer and are marked **[author]**;
the maintainer may reverse them, and each states what changes if they do.

---

## 1. Goal and non-goals

**Goal.** Let an MCP client index a JSON, CSV, XML or Markdown document set into a
Solr collection by naming a URL, so the payload never passes through the model's
context. Works identically in the STDIO and HTTP transports.

**Why.** Every existing indexing tool takes the payload as a tool-call argument. The
model must emit every byte. Measured on PR #197: indexing 61 documents through a live
server took over two minutes, of which Solr and the server accounted for under one
second; the rest was the model emitting ~9,500 tokens of JSON. Files larger than a
single response's output budget must be split across calls, each of which commits.
A URL argument is ~20 tokens regardless of payload size.

**Non-goals.**

- Schema definition from a file or URL. Schema payloads are a few KB, and the model
  should read and reconcile them against `get-schema` anyway.
- HTML pages. `text/html` is rejected, not converted (§3, D8).
- Authenticated or header-customised fetches (§3, D5).
- Client-to-server file transfer for the HTTP transport. MCP has no such primitive;
  SEP-2631 ("File Objects and Transfer") is the open draft that will add one. Until
  then a file selected in Claude Desktop under HTTP takes the **existing inline
  path**: the client places the content in the model's context and the model calls
  the per-format inline tool. That path is kept as-is and is the documented answer
  (§6.2); `index-url` is not a substitute for it because the URL resolves from the
  server's network, not the client's.
- Progress notifications. The HTTP transport runs `spring.ai.mcp.server.protocol=stateless`,
  which blocks server-to-client notifications for every tool; this spec does not
  change that.

---

## 2. What already exists on the branch (verified 2026-09-15)

The implementer should read these before writing code; the new tool is a second
adapter over them, not a new pipeline.

| Component | Location | Role |
|---|---|---|
| `IndexingService.indexFileDocuments(String collection, Reader input, String format)` | `indexing/IndexingService.java:628` | The streaming spine. Reads records from a `Reader`, batches 1,000 at a time via `indexBatch`, commits at the end, returns a human-readable summary string with counts and field names. |
| `IndexingService.FileIndexingProgress` | `indexing/IndexingService.java:644` | Batch accumulator used by the spine. |
| `IndexingDocumentCreator.stream(Reader, String format, Consumer<SolrInputDocument>)` | `indexing/documentcreator/IndexingDocumentCreator.java:109` | Dispatches to the per-format streaming parsers. |
| `BorrowedReader` | `indexing/documentcreator/BorrowedReader.java` | `FilterReader` that prevents parsers from closing the caller's reader. |
| `FileIndexingService` (`index-file` tool) | `indexing/FileIndexingService.java` | The existing adapter: path -> `Files.newBufferedReader` -> spine. Annotated `@Profile("stdio & !http")` + `@ConditionalOnNotWebApplication`. Its error-mapping style (§4.5) and `resolveFormat` switch are the templates for the new service. |
| `McpToolRegistrationTest.everyMcpEndpointIsPreAuthorized` | `src/test/java/.../McpToolRegistrationTest.java:237` | Fails the build if any `@McpTool` method lacks `@PreAuthorize`. |
| `McpClientIntegrationTest` (HTTP) / `McpClientStdioIntegrationTest` | `src/test/java/.../` | Full MCP round-trips over each transport against Testcontainers Solr. The HTTP one already asserts `index-file` is **absent** from `tools/list`. |

Upstream `main` has none of the streaming code. `git diff --stat upstream/main...HEAD`
at `318e086` is 23 files, 1,904 insertions, 178 deletions. Commit `318e086` (make
`required` explicit on `@McpToolParam`/`@McpArg`) and parts of `e7118fc` touch
`SearchService`, `CollectionService` and `SchemaService` and are unrelated to
ingestion; §8 splits them out.

---

## 3. Design decisions and why

**D1. Any `http` or `https` URL is accepted. No operator allow-list.**
*Why:* it mirrors the posture `index-file` already documents ("no ingest-root
setting or file-size cap; OS permissions and container mounts define the boundary").
The URL analogue is "the network the MCP server process can reach defines the
boundary". A deny-by-default allow-list was considered and rejected because it makes
the common case (a public raw-GitHub or S3 URL) require operator setup; per-transport
defaults were rejected because they fork the tool surface.
*Consequence:* in HTTP mode the reachable network includes RFC1918 services and the
server's own loopback. This is a real SSRF primitive and is **accepted as a
documented property** (§7), not a defect. It reverses the position recorded when PR
#194 was closed on 2026-09-13, which favoured a client-to-server push (SEP-2631 or an
upload endpoint) over any server-side fetch. The pull model is taken because it is
the only non-inline path both transports can reach identically today.

**D2. Link-local and cloud-metadata addresses are refused. [author]**
*Why:* the single worst outcome of D1 is a cloud instance handing its IAM
credentials to whoever can call the tool. Refusing `169.254.0.0/16`, `fe80::/10`
and `fd00:ec2::254` is not an allow-list and needs no configuration; it removes that
outcome without touching any legitimate use. Loopback and RFC1918 are deliberately
**not** refused (that would be an allow-list by another name and would break
"serve it locally" on a shared network).
*Consequence:* redirects must be followed manually so the check runs on every hop
(§4.3). DNS rebinding between the check and the connect is a known, documented gap.
*If reversed:* delete `UrlTargetPolicy`, use `HttpClient.Redirect.NORMAL`, and
drop the two policy tests; the §7 text changes from "refuses link-local" to "no
address restriction of any kind".

**D3. `index-file` stays, STDIO-only. [author, maintainer's call]**
*Why:* PR #194 was closed by its author on 2026-09-13 because a STDIO-only tool
"would give the two transports different tool surfaces for the same job", and named
SEP-2631 or an upload endpoint as the acceptable fixes. This spec keeps the same
STDIO-only bean anyway, for one reason: the flagship deployment is Claude Desktop +
STDIO with the file on the same machine, and without `index-file` that deployment's
most natural gesture ("index this file") takes the worst path (inline, output-capped,
multi-commit) while the file sits readable by the server process. With `index-url`
present, HTTP is no longer *without* a non-inline bulk path, so `index-file` becomes
"the zero-network shortcut STDIO can take because the file is local", not "the thing
STDIO has that HTTP lacks". The upload endpoint #194 named is **deferred, not
rejected** (§1).
*If reversed:* delete `FileIndexingService` and its test, drop the `index-file`
references from §6, and remove the "present under stdio" assertions in §5.

**D4. One `index-url` tool with an optional `format`, not four per-format tools.**
*Why:* PR #197 (fold the four inline tools into one) was closed because each inline
tool's signature is optimised for its format's token shape (CSV text is ~half the
tokens of escaped JSON). `index-url` carries no payload in its arguments, so there is
no shape to optimise, and one tool lets the common case infer the format from the
URL path.

**D5. No credentials and no caller-supplied request headers, ever.**
*Why:* `THREAT_MODEL.md` §12 names two separate model-changing conditions: a
backend *target* from a tool argument, and a *credential* from a tool argument. D1
opens the first. Sending no `Authorization`, no cookies and no caller headers keeps
the second closed, so the server can never act as a credential relay.
*Consequence:* authenticated or internal export endpoints are out of scope.

**D6. Format resolution: explicit `format` > URL path extension > `Content-Type` > error.**
*Why:* `raw.githubusercontent.com` serves `.json` as `text/plain; charset=utf-8`,
as do most static hosts, so `Content-Type` alone would mis-route the canonical
example. `text/plain` therefore maps to **nothing**. `text/html` maps to nothing so
that an ordinary web page cannot be parsed as CSV rows.

**D7. Non-2xx is a hard failure before any byte is parsed.**
*Why:* a GitHub 404 body is the literal text `404: Not Found`. JSON parsing would
reject it, but the CSV parser would index it as a valid document.

**D8. `text/html` is an error, not converted to text.**
*Why:* "index this web page" is a different feature (extraction, boilerplate
removal) and would be scope creep; an honest error that suggests `format=` is better
than a best-effort guess.

**D9. Body-level timeouts: idle watchdog on by default, byte cap off by default. [author]**
*Why:* `HttpClient`'s connect and response timeouts bound the handshake and headers
only. Two body failure modes remain. A *stalled* body blocks the call until the MCP
client's own timeout, which is the same exposure `index-file` has on a stalled NFS
mount. An *endless* body is different: a remote endpoint that never closes the stream
keeps the spine **adding** a batch of 1,000 documents every few seconds under the
remote party's control, with no filesystem analogue. (The spine commits only once,
at the end of the stream; added batches become durable through Solr's own
`autoCommit`, and nothing the server does on failure rolls them back.) A read-idle watchdog (fail if
no bytes arrive for 30 s) closes both cases cheaply. A byte cap is provided but
defaults to unlimited to match `index-file`'s "no size cap" posture.
*If reversed:* drop `IdleTimeoutInputStream`, keep the two `HttpClient` timeouts,
and add to §7 that partial commits from a hostile URL are accepted.

**D10. Up to 5 redirects are followed; the sixth is an error; never `https` to `http`.**
*Why:* release-asset and short-link URLs redirect routinely. Manual following is
required by D2; the hop limit prevents loops; the downgrade rule matches what
`HttpClient.Redirect.NORMAL` would have done.

**D11. Charset from `Content-Type`, else UTF-8. No gzip. No `file:`/`ftp:`.**
*Why:* the parsers are UTF-8-first like `index-file`. No `Accept-Encoding` header is
sent, so a compliant server sends identity. `file:` would duplicate `index-file` in
STDIO and be arbitrary local file read over HTTP, which is strictly worse than SSRF.

**D12. `index-url` is registered in both transports with no `@Profile` gate.**
*Why:* this is the whole point (§1) and what answers the #194 objection for the
URL path.

---

## 4. Server implementation

### 4.1 Tool contract

```
index-url(collection: String, url: String, format: String?) -> String
```

| Parameter | `required` | Description text (verbatim for `@McpToolParam`) |
|---|---|---|
| `collection` | `true` | `Solr collection to index into` |
| `url` | `true` | `Absolute http or https URL of a UTF-8 JSON, CSV, XML or Markdown document. Fetched from the MCP server's network, not the client's: localhost means the server. No credentials or custom headers are sent.` |
| `format` | `false` | `Optional format: json, csv, xml, markdown or md; defaults to the URL path extension, then the Content-Type` |

`@McpTool` attributes: `name = "index-url"`,
`annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = true)`.
`openWorldHint` is set because the tool contacts an external system chosen by the
caller; `index-file` does not set it and must not.

Description (verbatim):

> Index a UTF-8 JSON, CSV, XML or Markdown document set from an http(s) URL without
> sending its contents through the model. Available in both STDIO and HTTP mode. The
> URL is fetched from the MCP server's network position with no credentials or custom
> headers; link-local and cloud-metadata addresses are refused, anything else the
> server can reach is allowed. Redirects are followed. Structured records stream in
> batches; Markdown remains one document. Non-2xx responses and HTML pages are errors.
> Reuse the URL for another collection. Failures can leave partially indexed data;
> verify counts before retrying. *(+ `IndexingService.SCHEMA_FIRST_GUIDANCE`)*

Return value: the summary string produced by the spine (unchanged from `index-file`).

### 4.2 Classes

All new classes live in `org.apache.solr.mcp.server.indexing` unless stated.

| Class | Kind | Responsibility |
|---|---|---|
| `UrlIndexingService` | `@Service @Observed` | The `@McpTool` method; wires `HttpClient`, `UrlTargetPolicy`, `UrlFetcher`; error mapping (§4.5). The public constructor takes `IndexingService` and `UrlIndexingProperties` and **must carry `@Autowired`**, because a second package-private constructor `(IndexingService, UrlIndexingProperties, UrlFetcher)` exists for the unit test and Spring refuses to guess between two constructors. **No** `@Profile`, **no** `@ConditionalOnNotWebApplication`. Tool method carries `@PreAuthorize("isAuthenticated()")`. Owns one daemon `ScheduledExecutorService` for the watchdog, shut down in `@PreDestroy`. |
| `UrlIndexingProperties` | `record` + `@ConfigurationProperties("solr.index-url")` | `Duration connectTimeout` (10s), `Duration responseTimeout` (60s), `Duration idleTimeout` (30s), `DataSize maxBytes` (0 = unlimited). Register by adding it to the `@EnableConfigurationProperties` annotation on `config/SolrConfig.java`, which already registers `SolrConfigurationProperties`. |
| `UrlTargetPolicy` | final class, static `check(URI uri, List<InetAddress> resolved)` | Pure, no I/O: scheme, host, userinfo and D2 address checks. Throws only `IllegalArgumentException` with the §4.5 messages. The caller resolves the host. |
| `UrlFetcher` | package-private final class | Resolves the host (`InetAddress.getAllByName`; `UnknownHostException` -> the unreachable error), calls `UrlTargetPolicy.check`, performs the request with the manual redirect loop (§4.3); returns a `FetchedBody` record `(URI finalUri, int status, String mediaType, Charset charset, InputStream body)`. |
| `IndexFormats` | package-private final class, static `normalize(String keyword)` | The `json`/`csv`/`xml`/`md`/`markdown` keyword switch currently private in `FileIndexingService.resolveFormat`, extracted so both services share it. Returns the canonical format or throws the "supply format=" error. |
| `IdleTimeoutInputStream` | package-private `FilterInputStream` | D9 watchdog (§4.4). |
| `LimitedInputStream` | package-private `FilterInputStream` | D9 byte cap; throws `IOException` once `maxBytes` is exceeded when `maxBytes > 0`. |

Renames in `IndexingService` (two callers now, the "file" name no longer fits):
`indexFileDocuments` -> `indexStreamedDocuments`; `FileIndexingProgress` ->
`StreamingProgress`. Update `FileIndexingService` and tests accordingly. No
behavioural change.

### 4.3 Fetch algorithm

1. Validate: `collection` non-blank, `url` non-blank (the `required = true` flag
   makes null impossible; do not null-check).
2. Parse `url` with `URI.create` inside a try; reject `URISyntaxException` /
   `IllegalArgumentException`, non-absolute URIs, any scheme other than `http` or
   `https`, a null `getHost()` (Java returns null for an empty or unparsable
   authority such as `http:///x`), and a non-null `getUserInfo()` (embedded
   credentials contradict D5). A bare decimal host such as `http://2852039166/`
   parses as a host and `InetAddress.getAllByName` decodes it to
   `169.254.169.254`, so it is caught by the address check in step 3, not here.
3. Resolve `uri.getHost()` with `InetAddress.getAllByName`; `UnknownHostException`
   is the unreachable error. Then `UrlTargetPolicy.check(uri, addresses)`: for
   **every** resolved address, reject if `isLinkLocalAddress()` is true or the
   address equals `fd00:ec2::254`. Do not reject loopback, site-local or any-local
   addresses. (IPv4-mapped IPv6 literals come back from `getAllByName` as
   `Inet4Address`, so `::ffff:169.254.169.254` is caught by the same test.)
4. Build one `HttpClient` per service instance:
   `connectTimeout(properties.connectTimeout())`, `followRedirects(Redirect.NEVER)`,
   `version(HTTP_1_1)` is not required; leave default. No authenticator, no cookie
   handler.
5. Request: `GET`, `timeout(properties.responseTimeout())`, headers
   `Accept: application/json, text/csv, application/xml, text/xml, text/markdown, text/plain;q=0.5, */*;q=0.1`
   and `User-Agent: solr-mcp`. Nothing else, and nothing from the caller (D5).
6. Send with `BodyHandlers.ofInputStream()`.
7. If status is 301, 302, 303, 307 or 308 and a `Location` header is present:
   close the body; resolve `Location` against the current URI; reject if the current
   scheme is `https` and the new scheme is `http`; increment the redirect counter
   (starts at 0) and, if it is now greater than 5, fail with the redirect-loop error
   (so five redirects succeed and the sixth redirect *response* fails); go to step 2
   with the new URI, which re-runs the host, userinfo and policy checks. 303 switches
   the method to GET, which it already is.
8. If status is not 2xx: close the body; throw the HTTP-status error. Do not read
   or echo the body.
9. Media type = `Content-Type` value up to the first `;`, trimmed, lower-cased, or
   empty if absent. Charset = the `charset=` parameter if present and supported by
   `Charset.forName`, else UTF-8; an unsupported charset name is an error.
10. Resolve the format (§4.3.1). Throw the format error if unresolved.
11. Wrap the body: `LimitedInputStream` (if `maxBytes > 0`) inside
    `IdleTimeoutInputStream` inside `InputStreamReader(charset)` inside
    `BufferedReader`. Pass the reader to `indexingService.indexStreamedDocuments`.
12. Always close the body in a `finally`, including on the error paths above.

#### 4.3.1 Format resolution

Extension = the substring after the last `.` in the last segment of
`uri.getPath()` (query string and fragment are excluded by construction), lower-cased.
An explicit `format` argument wins if non-blank and is normalised by
`IndexFormats.normalize` (§4.2), the keyword switch extracted from
`FileIndexingService.resolveFormat` (`json`, `csv`, `xml`, `md`, `markdown`; anything
else is the "supply format=" error). `FileIndexingService` is changed to call the
same helper.

| Extension | Media type (parameters stripped) | Format |
|---|---|---|
| `json` | `application/json` | `json` |
| `csv` | `text/csv` | `csv` |
| `xml` | `application/xml`, `text/xml` | `xml` |
| `md`, `markdown` | `text/markdown` | `markdown` |
| anything else or none | `text/plain`, `text/html`, `application/octet-stream`, anything else, none | **error** |

Extension is consulted first; media type only if the extension did not match.

### 4.4 Idle watchdog

`IdleTimeoutInputStream(InputStream in, Duration idle, ScheduledExecutorService scheduler)`:

- Every `read` updates a `volatile long lastActivityNanos`.
- On construction, schedule a task at `idle / 2` intervals that, if
  `now - lastActivityNanos > idle`, sets a `volatile boolean timedOut` and calls
  `in.close()`, which makes the blocked `read` in the HTTP client throw.
- Any `IOException` from `in` while `timedOut` is true is rethrown as
  `IOException("No data received for " + idle + "; aborting")`.
- `close()` cancels the scheduled task and closes `in`.

Timing contract: because the check runs every `idle / 2`, the abort happens between
`idle` and `1.5 × idle` after the last byte. Tests assert "failed within `3 × idle`"
and "not failed before `idle`", nothing tighter.

Assumption to verify, not to trust: the JDK's `HttpResponseInputStream.close()`
offers a sentinel to its buffer queue so a `read()` blocked in another thread wakes
and throws. The `/stall` case in `UrlIndexingIntegrationTest` (§5) is the end-to-end
check; if it hangs, the fallback is to read the body on a virtual thread and have the
watchdog interrupt that thread instead.

One `ScheduledExecutorService` per `UrlIndexingService`, single daemon thread,
created in the constructor and shut down in a `@PreDestroy` method.

### 4.5 Error mapping

Follow `FileIndexingService` exactly: `IllegalArgumentException` for caller-fixable
problems, `IllegalStateException` for environment/Solr problems, log the cause at
`debug` (caller problems) or `warn` (Solr), and never put the URL's response body,
resolved IP, or stack detail in the message. The MCP framework turns both exception
types into tool errors.

| Condition | Exception | Message (verbatim) |
|---|---|---|
| blank `collection` | IAE | `Provide a non-empty collection name.` |
| blank / unparsable / relative / non-http(s) `url`, or null host | IAE | `Provide an absolute http or https URL.` |
| `url` contains userinfo (`user:pass@`) | IAE | `Remove the credentials from the URL; this server never sends credentials.` |
| policy rejection (D2) | IAE | `This server does not fetch link-local or cloud-metadata addresses.` |
| host does not resolve, connect failure, connect/response timeout | ISE | `Cannot reach the URL from the MCP server. The URL is fetched from the server's network, not the client's, so localhost and private addresses refer to the server's side. Check the address and try again.` |
| `https` -> `http` redirect | IAE | `The URL redirects from https to http, which is refused. Use the final https URL directly.` |
| more than 5 redirects | IAE | `The URL redirected more than 5 times. Use the final URL directly.` |
| non-2xx status *N* | IAE | `The URL returned HTTP N; nothing was indexed. Check that it is public and points at a raw document, not a web page.` |
| format unresolved | IAE | `Cannot determine the format from the URL path or Content-Type. Supply format=json, csv, xml or markdown.` (append ` HTML pages are not supported.` when the media type is `text/html`) |
| unsupported charset | IAE | `The URL declares an unsupported charset. Supply a UTF-8 document.` |
| `DocumentProcessingException` | IAE | `Cannot parse the URL content as <format>. Check its syntax and format. Some documents may already be indexed; verify the collection before retrying.` |
| `SolrServerException` / `SolrException` | ISE | `Solr could not complete URL indexing. Check collection availability and field types with get-schema, then verify the indexed count before retrying; some documents may already be indexed.` |
| idle timeout, byte cap, other `IOException` mid-body | ISE | `Cannot finish URL indexing: the download stalled or exceeded the configured limit. Some documents may already be indexed; verify the collection before retrying.` |

### 4.6 Configuration

Add to `application.properties` (not the profile files; the tool exists in both):

```properties
# index-url fetch limits. Connect/response bound the handshake and headers;
# idle-timeout aborts a body that stops delivering bytes; max-bytes=0 is unlimited.
solr.index-url.connect-timeout=10s
solr.index-url.response-timeout=60s
solr.index-url.idle-timeout=30s
solr.index-url.max-bytes=0
```

Each also readable from the environment as `SOLR_INDEX_URL_CONNECT_TIMEOUT` etc. via
Boot's relaxed binding; document the env names, not the property names, in §6.

### 4.7 Native image

`java.net.http.HttpClient` and `com.sun.net.httpserver.HttpServer` are JDK modules
and are expected to work in the GraalVM native binary without hints. **Verify this
first**: before writing tests, compile with `./gradlew nativeCompile -Pnative`, start
the binary, and call `index-url` with an `https://` URL. If it fails, stop and report;
the test design (§5) assumes the JDK server runs under `nativeTest`. A
`@ConfigurationProperties` record needs no reflection hint under Spring AOT. Do not
add anything to `SolrNativeHints` unless a native run proves it necessary.

---

## 5. Tests

Naming: `*Test` = unit (Mockito allowed, `@DisabledInNativeImage`),
`*IntegrationTest` = Testcontainers Solr. Zero skipped tests in `./gradlew build`; a
skipped count above zero there is a defect. Under `./gradlew nativeTest -Pnative`
the only skips allowed are whole classes annotated `@DisabledInNativeImage`
(Mockito), so the native skipped count must equal the count on `main` plus exactly
the new Mockito class (`UrlIndexingServiceTest`); anything else is a defect.

| Test | Kind | Asserts |
|---|---|---|
| `UrlTargetPolicyTest` | unit, no mocks (runs natively) | calls `check(uri, addresses)` with addresses built via `InetAddress.getByAddress` so no DNS is involved. Rejects: `169.254.169.254`, `fe80::1`, `fd00:ec2::254`, `::ffff:169.254.169.254`, a host list where only one of several addresses is link-local; `ftp://` and `file://` schemes; a relative URL; `http://user:pw@host/`; a URI whose `getHost()` is null. Accepts: `127.0.0.1`, `::1`, `10.0.0.1`, `192.168.1.1`, a public literal such as `93.184.216.34`. |
| `IdleTimeoutInputStreamTest` | unit, no mocks | uses a `PipedInputStream` (or a custom blocking stream) with `idle = 500ms`: a stream that delivers then blocks fails within `3 × idle` with the idle message and not before `idle`; a stream delivering one byte every `idle / 4` for `4 × idle` is not interrupted; `close()` cancels the task. |
| `UrlFetcherTest` | unit, no mocks (runs natively) | drives `UrlFetcher` against a JDK `HttpServer` on loopback with no Solr: media type and charset parsing, unsupported charset, 404 before any body, absolute and relative redirects, five redirects followed and the sixth refused, a redirect loop, the https→http downgrade rule (via the static `redirectTarget`), the policy re-run on a redirect to a metadata address, `UnknownHostException` for an unresolvable host, and a header-recording handler proving only `Accept` and `User-Agent` are sent and never `Authorization` or `Cookie`. |
| `UrlIndexingServiceTest` | unit, Mockito, `@DisabledInNativeImage` | error mapping table (§4.5) row by row using a mocked `IndexingService` and a mocked `UrlFetcher`; explicit `format` overrides extension; extension overrides media type; `?query` is ignored for extension; the body is closed on every path. |
| `UrlIndexingIntegrationTest` | integration | JDK `com.sun.net.httpserver.HttpServer` on an ephemeral port serving: `/shows.json`, `/shows.csv`, `/shows.xml`, `/shows.md` (real Solr, counts verified via search); `/export.csv?token=x` (CSV by extension); `/data` with `Content-Type: application/json` (JSON by media type); `/data.txt` with `text/plain` (format error); `/page` with `text/html` (HTML error); `/missing` 404 (nothing indexed); `/moved` -> 302 -> `/shows.json` (followed); `/loop` -> 302 -> `/loop` (loop error); a `/big.csv` handler that generates ≥ 200,000 rows on the fly with chunked transfer (never materialised on either side), asserting the indexed count from the summary and via `search` — `FileIndexingServiceTest.streamsFilesLargerThanTenMiBInBoundedBatches` is the template; a `/stall` handler that writes 1,200 rows then sleeps past `idle-timeout` (configure `solr.index-url.idle-timeout=2s` for this test): the call fails with the stall message within 3 × idle; the test then issues its own `solrClient.commit` and asserts `numFound == 1000`, proving that one full batch was added before the stall and the 200 unflushed rows were not. This test is also the end-to-end check that closing the JDK response stream from the watchdog thread wakes the blocked read (§4.4). A `/headers` handler records the request headers; assert no `Authorization`, no `Cookie`, and `User-Agent: solr-mcp`. |
| `McpClientIntegrationTestBase` | shared base of both client tests | `listToolsReturnsExpectedTools` asserts `index-url` is present, and `toolsExposeBehaviorHints` asserts its hints (`readOnly=false`, `destructive=true`, `idempotent=true`, `openWorld=true`). Because the base runs under both transports, these two assertions **are** the parity guard. It also provides `serveShowsJson()`, a loopback `HttpServer` serving `shows.json` as `text/plain`. |
| `McpClientIntegrationTest` (HTTP) | integration, existing | keeps asserting `index-file` is absent; adds one `index-url` round trip (create collection, index from the test server, `search` count = 61) and one refused-address call that must come back as an MCP tool error. |
| `McpClientStdioIntegrationTest` | integration, existing | asserts **both** `index-url` and `index-file` are present; adds the same `index-url` round trip alongside the existing `index-file` ones. |
| `McpToolRegistrationTest` | unit, existing | picks up the new tool automatically for `@PreAuthorize` and parameter-convention checks; add `UrlIndexingService` to its service list. It is reflection-only and **cannot** assert per-transport registration; that lives in the two client tests above. |
| `FileIndexingServiceTest`, `StreamingDocumentCreatorTest` | existing | unchanged apart from the renames in §4.2. |

The two client tests together pin D3: `index-file` present under stdio, absent under
http. A later refactor that exposes local file reads over HTTP fails the HTTP test.

---

## 6. Documentation and guidance edits

### 6.1 Model-facing text

**`IndexingService.indexDataPrompt`, step 3** — replace the first bullet (currently
ends "HTTP has no file tool.") with:

> - For JSON, CSV, XML or Markdown reachable at an http(s) URL, prefer `index-url`
>   with `collection` and `url`; optionally override the detected `format`. The URL is
>   fetched by the MCP server, so it must be reachable from the server's network.
> - For a file saved on the local STDIO server, prefer `index-file` with `collection`
>   and `path` (absolute server-side path; a container path when using Docker).
>   `index-file` is not available over HTTP.
> - Use one path only; do not also send inline data after a successful file or URL call.

and delete the sentence "Choose one ingestion path; do not also send inline data
after a successful file call." from the following "Otherwise, call …" bullet, which
the new third bullet replaces.

**`FileIndexingService` tool description** — replace "Available only in local STDIO
mode." with "Available only in local STDIO mode; for remote sources use `index-url`."
Replace "The path must be readable by the MCP server, not a URL or remote-client-only
path." with "The path must be readable by the MCP server, not a remote-client-only
path; for URLs use `index-url`."

**`spring.ai.mcp.server.instructions`** in `application.properties` — replace the
sentence "In local STDIO mode, prefer index-file … HTTP supports inline indexing
only." with:

> For bulk data at an http(s) URL, prefer index-url; in local STDIO mode, prefer
> index-file for saved files. Reuse URLs and server-side paths instead of repeating
> payloads; use inline indexing tools only for small pasted data.

### 6.2 User docs

- **`README.md` tool table**: add a row
  `| index-url | Index a UTF-8 JSON, CSV, XML or Markdown document from an http(s) URL (both transports; fetched from the server's network) |`
  directly above the `index-file` row. Keep the `index-file` row's "STDIO only".
- **`README.md`** configuration section: list the four `SOLR_INDEX_URL_*` env vars
  with defaults and one line: "index-url fetches from the server's network position
  with no credentials; link-local and cloud-metadata addresses are refused, anything
  else the server can reach is allowed. Run the HTTP transport on a network you are
  willing to expose to authenticated callers."
- **`docs/tutorial.md`** line 93 currently reads "HTTP has no file-ingestion tool;
  use `index-json-documents` with inline JSON there." Replace with a short section:
  in HTTP mode, use `index-url` with the raw GitHub URL of `shows.json`
  (`https://raw.githubusercontent.com/apache/solr-mcp/main/src/test/resources/shows.json`);
  a file selected in Claude Desktop goes through the inline tools exactly as today,
  because the client cannot send files to an MCP server and the server cannot see the
  client's disk; do not serve it on localhost and point `index-url` at it, since the
  URL is fetched from the server's side. Large attached files are split across calls
  by the model, each of which commits.
- **`docs/security/stdio.md`** and **`docs/security/http.md`**: both are cited by
  `THREAT_MODEL.md` §8.5 as documenting the backend-target invariant. Add one
  paragraph to each: `SOLR_URL` remains deployer-only config; `index-url` is the one
  tool that makes an outbound request to a caller-supplied address, it carries no
  credentials or caller headers, and its boundary is the network the server process
  can reach (for STDIO, the user's own machine; for HTTP, the deployment network,
  which is why the http.md paragraph also says not to deploy on a network with
  internal services you would not expose to every authenticated caller).

---

## 7. `THREAT_MODEL.md` edits (all required, in this order)

A security scanner reads §8 first. Without edit 1 the tool is in stated violation of
a critical property regardless of edits 2 to 8.

1. **§8 property 5** — change "so the AI client cannot repoint the server or inject
   a target URL" to "so the AI client cannot repoint the server's **Solr backend** or
   its credentials". Change the *Violation* clause to "a tool argument alters the
   Solr backend target or a credential". Append: "`index-url` performs an outbound
   GET to a caller-supplied `http(s)` URL; that is a §9 disclaimed property, not a
   backend target. It never carries credentials or caller-supplied headers."
2. **§9** — add: "**It does not restrict where `index-url` may fetch from.** The
   server issues an outbound `http(s)` GET to any address a caller names that the
   server process can reach, including loopback and RFC1918. Link-local and
   cloud-metadata addresses are refused; nothing else is. There is no allow-list. The
   fetch carries no credentials or caller headers."
3. **§11** — add: "**Running the HTTP transport on a network with reachable internal
   services** that you would not expose to every authenticated MCP caller —
   `index-url` lets such a caller fetch from them."
4. **§11a** — add: "**\"`index-url` allows SSRF.\"** `BY-DESIGN`: the tool's purpose
   is to fetch a caller-named URL, and the boundary is the network the server process
   can reach, parallel to `index-file`'s OS-permissions boundary. Link-local and
   cloud-metadata addresses are refused. A report is `VALID` only if it shows a
   credential or caller header being forwarded, a refused address being reached, or
   an `https`->`http` downgrade." Also update the existing `SOLR_URL` non-finding to
   say "the *Solr* target is deployer-only".
5. **§12** — replace the "backend-target or credential from a tool argument" bullet
   with: "Allowing a **credential** to originate from a tool argument (would open
   credential-relay). *The backend-target half of this condition was exercised on
   2026-09-15 by `index-url`; see §8.5 and §9.*"
6. **§13** — in the `VALID` row change "tool-arg repoints backend" to "tool-arg
   repoints the Solr backend or forwards a credential". In the
   `BY-DESIGN: property-disclaimed` row add "`index-url` fetch target" to the
   examples.
7. **§5a** — add rows for `SOLR_INDEX_URL_IDLE_TIMEOUT` (30s) and
   `SOLR_INDEX_URL_MAX_BYTES` (0 = unlimited): "bound how long / how much a single
   `index-url` call will pull from a remote endpoint; the connect and response
   timeouts are operational, not security-relevant."
8. **§5a "How HTTP mode enforces auth"** — the tool count "all 11 tools" becomes
   the new total; verify by counting `@McpTool` after the change.

---

## 8. Delivery and definition of done

**Two PRs against `main`, in this order.**

1. **Refactor PR**: commit `318e086` (explicit `required`, dead null checks) plus
   the `SearchService` / `CollectionService` / `SchemaService` edits from `e7118fc`.
   No ingestion code. This repo does not accept unrelated files in a PR.
2. **Ingestion PR**: the streaming spine, `index-file`, `index-url`, and every edit
   in §6 and §7 together. The spine is ~1,100 lines and is not reviewable without a
   consumer. The PR description must cite #194 and #197 and state that D1 and D3
   revise the position recorded on #194.

Both are independent of the open PRs #196, #202, #203, #205 and #207.

**Implementation order within PR 2:** §4.7 native spike -> renames -> `UrlTargetPolicy`
+ test -> `IdleTimeoutInputStream` + test -> `UrlFetcher` -> `UrlIndexingService` +
unit test -> integration test -> client tests -> §6 -> §7.

**Done means all of the following pass, run serially, with the outputs pasted in the
PR:**

```bash
./gradlew spotlessApply
./gradlew build                      # includes rat, buildSrc tests, integration tests
grep -L 'skipped="0"' build/test-results/test/*.xml    # must print nothing
./gradlew nativeTest -Pnative        # requires GraalVM JDK 25 on JAVA_HOME
# native: find the result directory the task reports (build/test-results/test-native/
# in current GraalVM build tools) and confirm every skipped class is @DisabledInNativeImage
grep -l 'skipped="[1-9]' build/test-results/test-native/*.xml
```

plus a manual STDIO run against the raw-GitHub `shows.json` URL in §6.2 and a manual
HTTP run of the same call through MCP Inspector.

**Explicitly out of scope for PR 2** (record as follow-ups, do not implement): an
upload endpoint for HTTP-mode local files; `text/html` extraction; caller-supplied
headers; a metrics/progress channel via `streamable` mode; the `/admin/metrics`
migration.
