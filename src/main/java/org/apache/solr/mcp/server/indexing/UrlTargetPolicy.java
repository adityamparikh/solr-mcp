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

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Pure checks that decide whether {@code index-url} may fetch a URL. There is
 * no allow-list: anything the server process can reach is allowed except
 * link-local and cloud-metadata addresses, which are refused because they would
 * hand an instance's credentials to any caller. The caller resolves the host so
 * this class never performs I/O and the checks apply to every redirect hop.
 */
final class UrlTargetPolicy {

	static final String INVALID_URL = "Provide an absolute http or https URL.";
	static final String EMBEDDED_CREDENTIALS = "Remove the credentials from the URL; this server never sends credentials.";
	static final String REFUSED_ADDRESS = "This server does not fetch link-local or cloud-metadata addresses.";

	/**
	 * The EC2 IPv6 instance-metadata address; not link-local, so listed explicitly.
	 */
	private static final InetAddress EC2_IPV6_METADATA = literal("fd00:ec2::254");

	private UrlTargetPolicy() {
	}

	/**
	 * Rejects URLs this server will not fetch.
	 *
	 * @param uri
	 *            the URL as supplied, or the target of a redirect
	 * @param resolved
	 *            every address the URL's host resolves to
	 * @throws IllegalArgumentException
	 *             if the URL is not absolute http(s) with a parsable host, embeds
	 *             credentials, or resolves to a refused address
	 */
	static void check(URI uri, List<InetAddress> resolved) {
		String scheme = uri.getScheme();
		if (scheme == null || uri.getHost() == null) {
			throw new IllegalArgumentException(INVALID_URL);
		}
		String lowered = scheme.toLowerCase(Locale.ROOT);
		if (!lowered.equals("http") && !lowered.equals("https")) {
			throw new IllegalArgumentException(INVALID_URL);
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException(EMBEDDED_CREDENTIALS);
		}
		for (InetAddress address : resolved) {
			if (address.isLinkLocalAddress() || address.equals(EC2_IPV6_METADATA)) {
				throw new IllegalArgumentException(REFUSED_ADDRESS);
			}
		}
	}

	private static InetAddress literal(String address) {
		try {
			return InetAddress.getByName(address);
		} catch (UnknownHostException e) {
			throw new IllegalStateException("Not a literal address: " + address, e);
		}
	}
}
