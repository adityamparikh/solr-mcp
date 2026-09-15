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

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limits on a single {@code index-url} fetch. Connect and response timeouts
 * bound the handshake and the response headers; the idle timeout aborts a body
 * that stops delivering bytes; {@code maxBytes} of zero means unlimited, which
 * matches {@code index-file}'s no-size-cap posture.
 *
 * @param connectTimeout
 *            TCP/TLS connect timeout ({@code SOLR_INDEX_URL_CONNECT_TIMEOUT})
 * @param responseTimeout
 *            time allowed until the response headers arrive
 *            ({@code SOLR_INDEX_URL_RESPONSE_TIMEOUT})
 * @param idleTimeout
 *            longest gap allowed between body bytes
 *            ({@code SOLR_INDEX_URL_IDLE_TIMEOUT})
 * @param maxBytes
 *            body size cap, zero for unlimited
 *            ({@code SOLR_INDEX_URL_MAX_BYTES})
 */
@ConfigurationProperties(prefix = "solr.index-url")
public record UrlIndexingProperties(@DefaultValue("10s") Duration connectTimeout,
		@DefaultValue("60s") Duration responseTimeout, @DefaultValue("30s") Duration idleTimeout,
		@DefaultValue("0") DataSize maxBytes) {
}
