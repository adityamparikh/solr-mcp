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
package org.apache.solr.mcp.server.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

@JsonTest
class SolrConfigUrlNormalizationTest {

	@Autowired
	private ObjectMapper objectMapper;

	@ParameterizedTest
	@CsvSource({"http://localhost:8983, http://localhost:8983/solr",
			"http://localhost:8983/, http://localhost:8983/solr",
			"http://localhost:8983/solr, http://localhost:8983/solr",
			"http://localhost:8983/solr/, http://localhost:8983/solr",
			"http://localhost:8983/custom/solr/, http://localhost:8983/custom/solr",
			// A host *named* "solr": the URL string contains "/solr/" inside the
			// authority, so matching the whole string would wrongly conclude the
			// path was already present and skip normalisation.
			"http://solr/, http://solr/solr", "http://solr:8983/, http://solr:8983/solr",
			"http://solr:8983, http://solr:8983/solr",
			// "mysolr" must not be mistaken for the "solr" path segment.
			"http://localhost:8983/mysolr/, http://localhost:8983/mysolr/solr"})
	void testUrlNormalization(String inputUrl, String expectedUrl) throws Exception {
		SolrConfigurationProperties testProperties = new SolrConfigurationProperties(inputUrl);
		SolrConfig solrConfig = new SolrConfig();

		try (SolrClient client = solrConfig.solrClient(testProperties, new JsonResponseParser(objectMapper))) {
			assertNotNull(client);

			var httpClient = assertInstanceOf(HttpJdkSolrClient.class, client);
			assertEquals(expectedUrl, httpClient.getBaseURL());
		}
	}

	/**
	 * A URL that is not an absolute HTTP(S) URL must fail loudly at startup.
	 *
	 * <p>
	 * {@code URI.create("localhost:8983/solr")} yields an <em>opaque</em> URI whose
	 * scheme is {@code localhost} and whose path is {@code null}; resolving against
	 * it silently discards the host and port, leaving a base URL of {@code /solr}.
	 * SolrJ accepts that without complaint, so the mistake would only surface later
	 * as a confusing request failure.
	 */
	@ParameterizedTest
	@ValueSource(
			strings = {"localhost:8983/solr", "localhost:8983", "/solr", "solr", "ftp://localhost:8983/solr",
					"http://local host:8983/solr"})
	void testRejectsUrlThatIsNotAnAbsoluteHttpUrl(String inputUrl) {
		SolrConfigurationProperties testProperties = new SolrConfigurationProperties(inputUrl);
		SolrConfig solrConfig = new SolrConfig();

		IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
				() -> solrConfig.solrClient(testProperties, new JsonResponseParser(objectMapper)));

		assertTrue(thrown.getMessage().contains("solr.url"),
				() -> "Message should name the offending property, was: " + thrown.getMessage());
		assertTrue(thrown.getMessage().contains(inputUrl),
				() -> "Message should echo the rejected value, was: " + thrown.getMessage());
	}
}
