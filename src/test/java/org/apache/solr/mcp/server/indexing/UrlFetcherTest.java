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

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the fetcher against a JDK HTTP server on an ephemeral loopback port:
 * no mocks, no Solr, runs natively.
 */
class UrlFetcherTest {

	private HttpServer server;
	private String base;
	private UrlFetcher fetcher;
	private final Map<String, List<String>> lastRequestHeaders = new ConcurrentHashMap<>();

	@BeforeEach
	void setUp() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/shows.json",
				ex -> respond(ex, 200, "application/json; charset=utf-8", "[{\"id\":\"1\"}]"));
		server.createContext("/latin1.csv", ex -> respond(ex, 200, "text/csv; charset=iso-8859-1", "id\n1\n"));
		server.createContext("/bad-charset", ex -> respond(ex, 200, "text/csv; charset=no-such-charset", "id\n"));
		server.createContext("/untyped", ex -> respond(ex, 200, null, "id\n1\n"));
		server.createContext("/missing", ex -> respond(ex, 404, "text/plain", "404: Not Found"));
		server.createContext("/moved", ex -> redirect(ex, base + "/shows.json"));
		server.createContext("/relative", ex -> redirect(ex, "/shows.json"));
		server.createContext("/loop", ex -> redirect(ex, base + "/loop"));
		server.createContext("/to-metadata", ex -> redirect(ex, "http://169.254.169.254/latest/meta-data/"));
		for (int i = 1; i <= 6; i++) {
			int hop = i;
			server.createContext("/chain" + hop,
					ex -> redirect(ex, base + (hop == 1 ? "/shows.json" : "/chain" + (hop - 1))));
		}
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
		fetcher = new UrlFetcher(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
				Duration.ofSeconds(5));
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void returnsBodyMediaTypeAndCharsetOfATwoHundredResponse() throws Exception {
		try (var fetched = fetcher.fetch(URI.create(base + "/shows.json"))) {
			assertEquals(200, fetched.status());
			assertEquals("application/json", fetched.mediaType());
			assertEquals(StandardCharsets.UTF_8, fetched.charset());
			assertEquals("[{\"id\":\"1\"}]", new String(fetched.body().readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void honoursADeclaredCharset() throws Exception {
		try (var fetched = fetcher.fetch(URI.create(base + "/latin1.csv"))) {
			assertEquals(StandardCharsets.ISO_8859_1, fetched.charset());
		}
	}

	@Test
	void defaultsToUtf8AndEmptyMediaTypeWithoutContentType() throws Exception {
		try (var fetched = fetcher.fetch(URI.create(base + "/untyped"))) {
			assertEquals("", fetched.mediaType());
			assertEquals(StandardCharsets.UTF_8, fetched.charset());
		}
	}

	@Test
	void rejectsAnUnsupportedCharset() {
		var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(URI.create(base + "/bad-charset")));
		assertEquals("The URL declares an unsupported charset. Supply a UTF-8 document.", e.getMessage());
	}

	@Test
	void nonTwoHundredIsAnErrorBeforeAnyBodyIsExposed() {
		var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(URI.create(base + "/missing")));
		assertEquals("The URL returned HTTP 404; nothing was indexed. "
				+ "Check that it is public and points at a raw document, not a web page.", e.getMessage());
	}

	@Test
	void followsAbsoluteAndRelativeRedirectsToTheFinalUrl() throws Exception {
		try (var fetched = fetcher.fetch(URI.create(base + "/moved"))) {
			assertEquals(URI.create(base + "/shows.json"), fetched.finalUri());
		}
		try (var fetched = fetcher.fetch(URI.create(base + "/relative"))) {
			assertEquals(URI.create(base + "/shows.json"), fetched.finalUri());
		}
	}

	@Test
	void followsFiveRedirectsButNotSix() throws Exception {
		try (var fetched = fetcher.fetch(URI.create(base + "/chain5"))) {
			assertEquals(200, fetched.status());
		}
		var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(URI.create(base + "/chain6")));
		assertEquals("The URL redirected more than 5 times. Use the final URL directly.", e.getMessage());
	}

	@Test
	void aRedirectLoopIsAnError() {
		var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(URI.create(base + "/loop")));
		assertEquals("The URL redirected more than 5 times. Use the final URL directly.", e.getMessage());
	}

	@Test
	void refusesAnHttpsToHttpDowngradeOnRedirect() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlFetcher.redirectTarget(URI.create("https://example.invalid/a"), "http://example.invalid/b"));
		assertEquals("The URL redirects from https to http, which is refused. Use the final https URL directly.",
				e.getMessage());
		assertEquals(URI.create("https://example.invalid/b"),
				UrlFetcher.redirectTarget(URI.create("http://example.invalid/a"), "https://example.invalid/b"));
	}

	@Test
	void rePoliciesEveryRedirectHop() {
		var e = assertThrows(IllegalArgumentException.class, () -> fetcher.fetch(URI.create(base + "/to-metadata")));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
	}

	@Test
	void refusesAMetadataAddressWithoutSendingARequest() {
		var e = assertThrows(IllegalArgumentException.class,
				() -> fetcher.fetch(URI.create("http://169.254.169.254/latest/meta-data/")));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
	}

	@Test
	void anUnresolvableHostIsAnIoException() {
		assertThrows(UnknownHostException.class, () -> fetcher.fetch(URI.create("http://nonexistent.invalid/x.json")));
	}

	@Test
	void sendsOnlyAcceptAndUserAgentAndNeverCredentials() throws Exception {
		try (var fetched = fetcher.fetch(URI.create(base + "/shows.json"))) {
			fetched.body().readAllBytes();
		}
		assertEquals(List.of("solr-mcp"), lastRequestHeaders.get("User-agent"));
		assertNotNull(lastRequestHeaders.get("Accept"));
		assertNull(lastRequestHeaders.get("Authorization"));
		assertNull(lastRequestHeaders.get("Cookie"));
	}

	private void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
		record(exchange);
		byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
		if (contentType != null) {
			exchange.getResponseHeaders().add("Content-Type", contentType);
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (var out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private void redirect(HttpExchange exchange, String location) throws IOException {
		record(exchange);
		exchange.getResponseHeaders().add("Location", location);
		exchange.sendResponseHeaders(302, -1);
		exchange.close();
	}

	private void record(HttpExchange exchange) {
		lastRequestHeaders.clear();
		exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k, List.copyOf(v)));
	}
}
