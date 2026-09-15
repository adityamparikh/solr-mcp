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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LimitedInputStreamTest {

	private static final byte[] TEN = "0123456789".getBytes();

	@Test
	void readsExactlyTheLimit() throws IOException {
		try (var stream = new LimitedInputStream(new ByteArrayInputStream(TEN), 10)) {
			assertEquals(10, stream.readAllBytes().length);
		}
	}

	@Test
	void failsOnTheByteAfterTheLimit() throws IOException {
		try (var stream = new LimitedInputStream(new ByteArrayInputStream(TEN), 9)) {
			for (int i = 0; i < 9; i++) {
				assertNotEquals(-1, stream.read());
			}
			var e = assertThrows(IOException.class, stream::read);
			assertEquals("Body exceeded the configured limit of 9 bytes", e.getMessage());
		}
	}

	@Test
	void failsWhenABulkReadCrossesTheLimit() throws IOException {
		try (var stream = new LimitedInputStream(new ByteArrayInputStream(TEN), 4)) {
			byte[] buffer = new byte[8];
			var e = assertThrows(IOException.class, () -> stream.read(buffer, 0, buffer.length));
			assertEquals("Body exceeded the configured limit of 4 bytes", e.getMessage());
		}
	}
}
