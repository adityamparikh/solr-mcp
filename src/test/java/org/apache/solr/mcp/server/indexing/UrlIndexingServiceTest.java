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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

/**
 * Error mapping and format resolution of the {@code index-url} tool with the
 * fetcher and the spine mocked; the real fetch is covered by
 * {@link UrlFetcherTest} and the integration test.
 */
@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class UrlIndexingServiceTest {

	private static final String URL = "http://example.invalid/shows.json";
	private static final String SUMMARY = "Successfully indexed 2 of 2 documents into collection 'shows'";

	@Mock
	IndexingService indexingService;

	@Mock
	UrlFetcher fetcher;

	private UrlIndexingService service;

	@BeforeEach
	void setUp() {
		var properties = new UrlIndexingProperties(Duration.ofSeconds(10), Duration.ofSeconds(60),
				Duration.ofSeconds(30), DataSize.ofBytes(0));
		service = new UrlIndexingService(indexingService, properties, fetcher);
	}

	@AfterEach
	void tearDown() {
		service.shutdown();
	}

	@Test
	void streamsTheBodyIntoTheSpineAndReturnsItsSummary() throws Exception {
		var body = new ClosableBody("[{\"id\":\"1\"}]");
		when(fetcher.fetch(URI.create(URL))).thenReturn(fetched(URL, "application/json", body));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("json"))).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", URL, null));
		assertTrue(body.closed, "response body left open");
	}

	@Test
	void explicitFormatOverridesExtensionAndMediaType() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", new ClosableBody("id\n1\n")));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("csv"))).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", URL, " CSV "));
	}

	@Test
	void extensionOverridesMediaTypeAndIgnoresTheQueryString() throws Exception {
		String url = "http://example.invalid/export.csv?token=abc";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/plain", new ClosableBody("id\n1\n")));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("csv"))).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
	}

	@Test
	void mediaTypeResolvesTheFormatWhenTheExtensionDoesNot() throws Exception {
		String url = "http://example.invalid/data";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/xml", new ClosableBody("<add/>")));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("xml"))).thenReturn(SUMMARY);

		assertEquals(SUMMARY, service.indexUrl("shows", url, null));
	}

	@Test
	void textPlainWithoutAKnownExtensionIsAFormatError() throws Exception {
		String url = "http://example.invalid/data.txt";
		var body = new ClosableBody("id\n1\n");
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/plain", body));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals("Cannot determine the format from the URL path or Content-Type. "
				+ "Supply format=json, csv, xml or markdown.", e.getMessage());
		assertTrue(body.closed, "response body left open");
		verifyNoInteractions(indexingService);
	}

	@Test
	void htmlIsAFormatErrorThatSaysSo() throws Exception {
		String url = "http://example.invalid/page";
		when(fetcher.fetch(any())).thenReturn(fetched(url, "text/html", new ClosableBody("<html/>")));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", url, null));
		assertEquals("Cannot determine the format from the URL path or Content-Type. "
				+ "Supply format=json, csv, xml or markdown. HTML pages are not supported.", e.getMessage());
	}

	@Test
	void anUnknownExplicitFormatIsRejectedBeforeFetching() throws Exception {
		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", URL, "yaml"));
		assertEquals(IndexFormats.UNKNOWN_FORMAT, e.getMessage());
		verifyNoInteractions(fetcher, indexingService);
	}

	@Test
	void blankCollectionAndUrlAreRejectedBeforeFetching() throws Exception {
		var c = assertThrows(IllegalArgumentException.class, () -> service.indexUrl(" ", URL, null));
		assertEquals("Provide a non-empty collection name.", c.getMessage());
		var u = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", " ", null));
		assertEquals(UrlTargetPolicy.INVALID_URL, u.getMessage());
		var p = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", "http://[bad", null));
		assertEquals(UrlTargetPolicy.INVALID_URL, p.getMessage());
		verifyNoInteractions(fetcher, indexingService);
	}

	@Test
	void fetcherArgumentErrorsPassThroughUnchanged() throws Exception {
		when(fetcher.fetch(any())).thenThrow(new IllegalArgumentException(UrlTargetPolicy.REFUSED_ADDRESS));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(UrlTargetPolicy.REFUSED_ADDRESS, e.getMessage());
	}

	@Test
	void networkFailuresAreReportedFromTheServersPointOfView() throws Exception {
		when(fetcher.fetch(any())).thenThrow(new UnknownHostException("example.invalid"));

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals("Cannot reach the URL from the MCP server. The URL is fetched from the server's network, "
				+ "not the client's, so localhost and private addresses refer to the server's side. "
				+ "Check the address and try again.", e.getMessage());
	}

	@Test
	void parseFailuresNameTheFormatAndWarnAboutPartialData() throws Exception {
		var body = new ClosableBody("not json");
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", body));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("json")))
				.thenThrow(new DocumentProcessingException("bad"));

		var e = assertThrows(IllegalArgumentException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(
				"Cannot parse the URL content as json. Check its syntax and format. "
						+ "Some documents may already be indexed; verify the collection before retrying.",
				e.getMessage());
		assertTrue(body.closed, "response body left open");
	}

	@Test
	void solrFailuresAreStateErrors() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", new ClosableBody("[]")));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("json")))
				.thenThrow(new SolrServerException("down"));

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals("Solr could not complete URL indexing. Check collection availability and field types "
				+ "with get-schema, then verify the indexed count before retrying; some documents may already "
				+ "be indexed.", e.getMessage());
	}

	@Test
	void bodyFailuresMidStreamAreStateErrors() throws Exception {
		when(fetcher.fetch(any())).thenReturn(fetched(URL, "application/json", new ClosableBody("[")));
		when(indexingService.indexStreamedDocuments(eq("shows"), any(Reader.class), eq("json")))
				.thenThrow(new IOException("No data received for PT30S; aborting"));

		var e = assertThrows(IllegalStateException.class, () -> service.indexUrl("shows", URL, null));
		assertEquals(
				"Cannot finish URL indexing: the download stalled or exceeded the configured limit. "
						+ "Some documents may already be indexed; verify the collection before retrying.",
				e.getMessage());
	}

	private static UrlFetcher.FetchedBody fetched(String url, String mediaType, ClosableBody body) {
		return new UrlFetcher.FetchedBody(URI.create(url), 200, mediaType, StandardCharsets.UTF_8, body);
	}

	private static final class ClosableBody extends ByteArrayInputStream {
		volatile boolean closed;

		ClosableBody(String content) {
			super(content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}
}
