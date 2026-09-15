/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.mcp.server.indexing;

import io.micrometer.observation.annotation.Observed;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * URL ingestion for both transports. The connected client can point the server
 * at any http(s) URL the server process can reach, except link-local and
 * cloud-metadata addresses; the network the server can reach defines the
 * boundary, and the fetch never carries credentials or caller headers. The body
 * is streamed into the same spine {@code index-file} uses.
 */
@Service
@Observed
public class UrlIndexingService {

	private static final Logger logger = LoggerFactory.getLogger(UrlIndexingService.class);

	static final String UNREACHABLE = "Cannot reach the URL from the MCP server. The URL is fetched from the "
			+ "server's network, not the client's, so localhost and private addresses refer to the server's side. "
			+ "Check the address and try again.";
	static final String FORMAT_UNRESOLVED = "Cannot determine the format from the URL path or Content-Type. "
			+ "Supply format=json, csv, xml or markdown.";
	static final String HTML_NOT_SUPPORTED = " HTML pages are not supported.";
	static final String BODY_FAILED = "Cannot finish URL indexing: the download stalled or exceeded the configured "
			+ "limit. Some documents may already be indexed; verify the collection before retrying.";
	static final String SOLR_FAILED = "Solr could not complete URL indexing. Check collection availability and "
			+ "field types with get-schema, then verify the indexed count before retrying; some documents may "
			+ "already be indexed.";

	private final IndexingService indexingService;
	private final UrlIndexingProperties properties;
	private final UrlFetcher fetcher;
	private final ScheduledExecutorService watchdogs;

	/**
	 * Creates the URL-ingestion adapter with a JDK HTTP client that never follows
	 * redirects itself (the fetcher does, re-checking every hop).
	 *
	 * @param indexingService
	 *            the indexing pipeline
	 * @param properties
	 *            fetch limits
	 */
	public UrlIndexingService(IndexingService indexingService, UrlIndexingProperties properties) {
		this(indexingService, properties,
				new UrlFetcher(HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
						.followRedirects(HttpClient.Redirect.NEVER).build(), properties.responseTimeout()));
	}

	UrlIndexingService(IndexingService indexingService, UrlIndexingProperties properties, UrlFetcher fetcher) {
		this.indexingService = indexingService;
		this.properties = properties;
		this.fetcher = fetcher;
		this.watchdogs = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "index-url-watchdog");
			thread.setDaemon(true);
			return thread;
		});
	}

	/** Stops the idle watchdog thread. */
	@PreDestroy
	void shutdown() {
		watchdogs.shutdownNow();
	}

	/**
	 * Streams a document set from a URL into Solr without returning its contents.
	 *
	 * @param collection
	 *            target collection with a prepared schema
	 * @param url
	 *            absolute http(s) URL reachable from the server
	 * @param format
	 *            optional format override; otherwise inferred from the URL path
	 *            extension, then the Content-Type
	 * @return indexed document counts and field names
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(
			name = "index-url",
			annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = true),
			description = "Index a UTF-8 JSON, CSV, XML or Markdown document set from an http(s) URL without "
					+ "sending its contents through the model. Available in both STDIO and HTTP mode. The URL is "
					+ "fetched from the MCP server's network position with no credentials or custom headers; "
					+ "link-local and cloud-metadata addresses are refused, anything else the server can reach is "
					+ "allowed. Redirects are followed. Structured records stream in batches; Markdown remains one "
					+ "document. Non-2xx responses and HTML pages are errors. Reuse the URL for another collection. "
					+ "Failures can leave partially indexed data; verify counts before retrying. "
					+ IndexingService.SCHEMA_FIRST_GUIDANCE)
	public String indexUrl(
			@McpToolParam(description = "Solr collection to index into", required = true) String collection,
			@McpToolParam(
					description = "Absolute http or https URL of a UTF-8 JSON, CSV, XML or Markdown document. "
							+ "Fetched from the MCP server's network, not the client's: localhost means the server. "
							+ "No credentials or custom headers are sent.",
					required = true) String url,
			@McpToolParam(
					description = "Optional format: json, csv, xml, markdown or md; defaults to the URL path "
							+ "extension, then the Content-Type",
					required = false) @Nullable String format) {
		if (collection.isBlank()) {
			throw new IllegalArgumentException("Provide a non-empty collection name.");
		}
		URI uri = parse(url);
		@Nullable String explicit = format == null || format.isBlank() ? null : IndexFormats.normalize(format);
		UrlFetcher.FetchedBody fetched;
		try {
			fetched = fetcher.fetch(uri);
		} catch (IOException e) {
			logger.debug("Could not fetch URL for indexing", e);
			throw new IllegalStateException(UNREACHABLE);
		}
		try (fetched) {
			String selected = explicit != null ? explicit : resolveFormat(uri, fetched.finalUri(), fetched.mediaType());
			return stream(collection, fetched, selected);
		} catch (IOException e) {
			logger.debug("Closing the URL body failed after indexing", e);
			throw new IllegalStateException(BODY_FAILED);
		}
	}

	private String stream(String collection, UrlFetcher.FetchedBody fetched, String format) {
		InputStream in = fetched.body();
		long maxBytes = properties.maxBytes().toBytes();
		if (maxBytes > 0) {
			in = new LimitedInputStream(in, maxBytes);
		}
		in = new IdleTimeoutInputStream(in, properties.idleTimeout(), watchdogs);
		try (var reader = new BufferedReader(new InputStreamReader(in, fetched.charset()))) {
			return indexingService.indexStreamedDocuments(collection, reader, format);
		} catch (DocumentProcessingException e) {
			logger.debug("Could not parse URL content for indexing", e);
			throw new IllegalArgumentException("Cannot parse the URL content as " + format
					+ ". Check its syntax and format. Some documents may already be indexed; verify the collection "
					+ "before retrying.");
		} catch (SolrServerException | SolrException e) {
			logger.warn("URL indexing failed for collection {}", collection, e);
			throw new IllegalStateException(SOLR_FAILED);
		} catch (IOException e) {
			logger.debug("URL body read failed during indexing", e);
			throw new IllegalStateException(BODY_FAILED);
		}
	}

	private static URI parse(String url) {
		if (url.isBlank()) {
			throw new IllegalArgumentException(UrlTargetPolicy.INVALID_URL);
		}
		try {
			return URI.create(url.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(UrlTargetPolicy.INVALID_URL);
		}
	}

	/**
	 * Explicit format wins (handled by the caller); then the requested URL's path
	 * extension, then the final URL's after redirects, then the media type.
	 * {@code text/plain} carries no information and never resolves.
	 */
	private static String resolveFormat(URI requested, URI finalUri, String mediaType) {
		for (URI candidate : new URI[]{requested, finalUri}) {
			@Nullable String extension = extensionOf(candidate);
			if (extension != null) {
				try {
					return IndexFormats.normalize(extension);
				} catch (IllegalArgumentException ignored) {
					// not a supported extension; fall through to the next source
				}
			}
		}
		return switch (mediaType) {
			case "application/json" -> "json";
			case "text/csv" -> "csv";
			case "application/xml", "text/xml" -> "xml";
			case "text/markdown" -> "markdown";
			case "text/html" -> throw new IllegalArgumentException(FORMAT_UNRESOLVED + HTML_NOT_SUPPORTED);
			default -> throw new IllegalArgumentException(FORMAT_UNRESOLVED);
		};
	}

	private static @Nullable String extensionOf(URI uri) {
		String path = uri.getPath();
		if (path == null) {
			return null;
		}
		String last = path.substring(path.lastIndexOf('/') + 1);
		int dot = last.lastIndexOf('.');
		return dot < 0 || dot == last.length() - 1 ? null : last.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
