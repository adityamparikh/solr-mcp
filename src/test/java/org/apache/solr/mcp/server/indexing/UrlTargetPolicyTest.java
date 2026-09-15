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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pure checks on the URL and its resolved addresses; no DNS is involved because
 * every address is built from a literal.
 */
class UrlTargetPolicyTest {

	private static final String INVALID_URL = "Provide an absolute http or https URL.";
	private static final String CREDENTIALS = "Remove the credentials from the URL; this server never sends credentials.";
	private static final String REFUSED = "This server does not fetch link-local or cloud-metadata addresses.";

	@ParameterizedTest
	@ValueSource(strings = {"169.254.169.254", "fe80::1", "fd00:ec2::254", "::ffff:169.254.169.254"})
	void refusesLinkLocalAndCloudMetadataAddresses(String literal) throws UnknownHostException {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(URI.create("http://example.invalid/data.json"), List.of(literal(literal))));
		assertEquals(REFUSED, e.getMessage());
	}

	@Test
	void refusesWhenAnyOfSeveralAddressesIsLinkLocal() throws UnknownHostException {
		var addresses = List.of(literal("93.184.216.34"), literal("169.254.169.254"));
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(URI.create("http://example.invalid/data.json"), addresses));
		assertEquals(REFUSED, e.getMessage());
	}

	@ParameterizedTest
	@ValueSource(strings = {"127.0.0.1", "::1", "10.0.0.1", "192.168.1.1", "93.184.216.34"})
	void allowsLoopbackPrivateAndPublicAddresses(String literal) throws UnknownHostException {
		assertDoesNotThrow(
				() -> UrlTargetPolicy.check(URI.create("http://example.invalid/data.json"), List.of(literal(literal))));
	}

	@ParameterizedTest
	@ValueSource(
			strings = {"ftp://example.invalid/data.json", "file:///etc/passwd", "data.json", "/data.json",
					"http:///data.json", "http://user@/data.json"})
	void rejectsNonHttpSchemesRelativeUrlsAndMissingHosts(String url) throws UnknownHostException {
		var e = assertThrows(IllegalArgumentException.class,
				() -> UrlTargetPolicy.check(URI.create(url), List.of(literal("93.184.216.34"))));
		assertEquals(INVALID_URL, e.getMessage());
	}

	@Test
	void rejectsEmbeddedCredentials() throws UnknownHostException {
		var e = assertThrows(IllegalArgumentException.class, () -> UrlTargetPolicy
				.check(URI.create("http://user:pw@example.invalid/data.json"), List.of(literal("93.184.216.34"))));
		assertEquals(CREDENTIALS, e.getMessage());
	}

	@Test
	void acceptsHttps() throws UnknownHostException {
		assertDoesNotThrow(() -> UrlTargetPolicy.check(URI.create("https://example.invalid/data.json"),
				List.of(literal("93.184.216.34"))));
	}

	private static InetAddress literal(String literal) throws UnknownHostException {
		// getByName does not touch DNS for a literal IP address
		return InetAddress.getByName(literal);
	}
}
