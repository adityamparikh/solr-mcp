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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Performs the {@code index-url} GET: resolves and policy-checks the host on
 * every hop, follows up to {@value #MAX_REDIRECTS} redirects without
 * downgrading to plain http, sends no credentials or caller headers, and hands
 * back the still-open body of a 2xx response together with its media type and
 * charset. Caller-fixable problems surface as {@link IllegalArgumentException}
 * with the messages the tool returns verbatim; network failures surface as
 * {@link IOException}.
 */
final class UrlFetcher {

	static final int MAX_REDIRECTS = 5;
	static final String ACCEPT = "application/json, text/csv, application/xml, text/xml, text/markdown, "
			+ "text/plain;q=0.5, */*;q=0.1";
	static final String USER_AGENT = "solr-mcp";
	static final String TOO_MANY_REDIRECTS = "The URL redirected more than " + MAX_REDIRECTS
			+ " times. Use the final URL directly.";
	static final String DOWNGRADE = "The URL redirects from https to http, which is refused. "
			+ "Use the final https URL directly.";
	static final String UNSUPPORTED_CHARSET = "The URL declares an unsupported charset. Supply a UTF-8 document.";

	private final HttpClient client;
	private final Duration responseTimeout;

	/**
	 * A 2xx response whose body has not been read. Closing it closes the body.
	 *
	 * @param finalUri
	 *            the URL that answered, after redirects
	 * @param status
	 *            the 2xx status code
	 * @param mediaType
	 *            lower-cased media type without parameters, or empty if the
	 *            response carried no {@code Content-Type}
	 * @param charset
	 *            the declared charset, or UTF-8 when none was declared
	 * @param body
	 *            the streaming response body
	 */
	record FetchedBody(URI finalUri, int status, String mediaType, Charset charset,
			InputStream body) implements Closeable {
		@Override
		public void close() throws IOException {
			body.close();
		}
	}

	UrlFetcher(HttpClient client, Duration responseTimeout) {
		this.client = client;
		this.responseTimeout = responseTimeout;
	}

	/**
	 * Fetches a URL, following redirects.
	 *
	 * @param uri
	 *            the caller-supplied URL
	 * @return the open 2xx response
	 * @throws IllegalArgumentException
	 *             for a refused URL, too many redirects, an https to http
	 *             downgrade, a non-2xx status, or an unsupported charset
	 * @throws IOException
	 *             if the host does not resolve or the request fails
	 */
	FetchedBody fetch(URI uri) throws IOException {
		URI current = uri;
		int redirects = 0;
		while (true) {
			UrlTargetPolicy.check(current, List.of()); // scheme, host and userinfo before any DNS lookup
			UrlTargetPolicy.check(current, List.of(InetAddress.getAllByName(current.getHost())));
			HttpResponse<InputStream> response = send(current);
			int status = response.statusCode();
			if (isRedirect(status)) {
				var location = response.headers().firstValue("Location");
				if (location.isPresent()) {
					response.body().close();
					if (++redirects > MAX_REDIRECTS) {
						throw new IllegalArgumentException(TOO_MANY_REDIRECTS);
					}
					current = redirectTarget(current, location.get());
					continue;
				}
			}
			if (status / 100 != 2) {
				response.body().close();
				throw new IllegalArgumentException("The URL returned HTTP " + status + "; nothing was indexed. "
						+ "Check that it is public and points at a raw document, not a web page.");
			}
			String contentType = response.headers().firstValue("Content-Type").orElse("");
			Charset charset;
			try {
				charset = charsetOf(contentType);
			} catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
				response.body().close();
				throw new IllegalArgumentException(UNSUPPORTED_CHARSET);
			}
			return new FetchedBody(current, status, mediaTypeOf(contentType), charset, response.body());
		}
	}

	/**
	 * Resolves a {@code Location} header against the current URL, refusing an https
	 * to http downgrade.
	 */
	static URI redirectTarget(URI current, String location) {
		URI target = current.resolve(location);
		if ("https".equalsIgnoreCase(current.getScheme()) && "http".equalsIgnoreCase(target.getScheme())) {
			throw new IllegalArgumentException(DOWNGRADE);
		}
		return target;
	}

	private HttpResponse<InputStream> send(URI uri) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(responseTimeout).header("Accept", ACCEPT)
				.header("User-Agent", USER_AGENT).build();
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while fetching " + uri.getHost(), e);
		}
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static String mediaTypeOf(String contentType) {
		int semicolon = contentType.indexOf(';');
		String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
		return type.trim().toLowerCase(Locale.ROOT);
	}

	private static Charset charsetOf(String contentType) {
		for (String parameter : contentType.split(";")) {
			String trimmed = parameter.trim();
			if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
				String name = trimmed.substring(8).trim();
				if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
					name = name.substring(1, name.length() - 1);
				}
				return Charset.forName(name);
			}
		}
		return StandardCharsets.UTF_8;
	}
}
