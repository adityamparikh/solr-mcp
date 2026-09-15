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

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The watchdog checks every {@code idle / 2}, so an abort lands between
 * {@code idle} and {@code 1.5 x idle}; assertions use {@code 3 x idle} as the
 * upper bound and {@code idle} as the lower bound, nothing tighter.
 */
class IdleTimeoutInputStreamTest {

	private static final Duration IDLE = Duration.ofMillis(500);

	private ScheduledExecutorService scheduler;
	private BlockingSource source;

	@BeforeEach
	void setUp() {
		scheduler = Executors.newSingleThreadScheduledExecutor();
		source = new BlockingSource();
	}

	@AfterEach
	void tearDown() {
		scheduler.shutdownNow();
	}

	@Test
	void aStreamThatStopsDeliveringFailsAfterTheIdlePeriod() throws Exception {
		try (var stream = new IdleTimeoutInputStream(source, IDLE, scheduler)) {
			source.deliver('a');
			assertEquals('a', stream.read());
			long start = System.nanoTime();
			var e = assertThrows(IOException.class, stream::read); // blocks: nothing more is delivered
			long elapsed = System.nanoTime() - start;
			assertEquals("No data received for " + IDLE + "; aborting", e.getMessage());
			assertTrue(elapsed >= IDLE.toNanos(), "aborted before the idle period elapsed");
			assertTrue(elapsed <= IDLE.multipliedBy(3).toNanos(), "aborted later than 3 x idle");
		}
	}

	@Test
	void aSlowButSteadyStreamIsNotInterrupted() throws Exception {
		try (var stream = new IdleTimeoutInputStream(source, IDLE, scheduler)) {
			Thread writer = new Thread(() -> {
				try {
					for (int i = 0; i < 16; i++) {
						source.deliver('x');
						Thread.sleep(IDLE.toMillis() / 4);
					}
					source.endOfStream();
				} catch (InterruptedException e) {
					throw new RuntimeException(e);
				}
			});
			writer.start();
			byte[] all = stream.readAllBytes();
			writer.join();
			assertEquals(16, all.length);
		}
	}

	@Test
	void closingCancelsTheWatchdogAndClosesTheUnderlyingStream() throws Exception {
		var stream = new IdleTimeoutInputStream(source, IDLE, scheduler);
		stream.close();
		assertTrue(source.closed, "underlying stream still open after close");
		scheduler.shutdown();
		assertTrue(scheduler.awaitTermination(IDLE.toMillis() * 3, TimeUnit.MILLISECONDS),
				"watchdog task still running after close");
	}

	/**
	 * Blocks on read until a byte is delivered, like the JDK's response body
	 * stream; {@code close()} from another thread wakes a blocked read, which then
	 * throws.
	 */
	private static final class BlockingSource extends InputStream {

		private static final int CLOSED = -2;
		private static final int END = -1;
		private final LinkedBlockingQueue<Integer> bytes = new LinkedBlockingQueue<>();
		volatile boolean closed;

		void deliver(int b) {
			bytes.add(b);
		}

		void endOfStream() {
			bytes.add(END);
		}

		@Override
		public int read() throws IOException {
			if (closed) {
				throw new IOException("closed");
			}
			try {
				int b = bytes.take();
				if (b == CLOSED) {
					throw new IOException("closed");
				}
				return b;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}

		/**
		 * Like the JDK response stream: blocks for the first byte, then returns only
		 * what is already available instead of filling the buffer.
		 */
		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int first = read();
			if (first == END) {
				return -1;
			}
			b[off] = (byte) first;
			int n = 1;
			Integer next;
			while (n < len && (next = bytes.peek()) != null && next != END && next != CLOSED) {
				b[off + n++] = (byte) (int) bytes.poll();
			}
			return n;
		}

		@Override
		public void close() {
			closed = true;
			bytes.add(CLOSED);
		}
	}
}
