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

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Aborts a body that stops delivering bytes. A scheduled task checks every
 * {@code idle / 2} whether any read has made progress within {@code idle}; if
 * not, it closes the underlying stream, which wakes a blocked read, and the
 * resulting {@link IOException} is rethrown with an idle-timeout message. The
 * abort therefore lands between {@code idle} and {@code 1.5 x idle} after the
 * last byte. This closes both the stalled-body and the endless-body cases that
 * connect and response-header timeouts cannot.
 */
final class IdleTimeoutInputStream extends FilterInputStream {

	private final Duration idle;
	private final ScheduledFuture<?> watchdog;
	private volatile long lastActivityNanos;
	private volatile boolean timedOut;

	IdleTimeoutInputStream(InputStream in, Duration idle, ScheduledExecutorService scheduler) {
		super(in);
		this.idle = idle;
		this.lastActivityNanos = System.nanoTime();
		long halfMillis = Math.max(1, idle.toMillis() / 2);
		this.watchdog = scheduler.scheduleWithFixedDelay(this::checkProgress, halfMillis, halfMillis,
				TimeUnit.MILLISECONDS);
	}

	private void checkProgress() {
		if (System.nanoTime() - lastActivityNanos > idle.toNanos()) {
			timedOut = true;
			watchdog.cancel(false);
			try {
				in.close();
			} catch (IOException ignored) {
				// the blocked reader surfaces the failure
			}
		}
	}

	@Override
	public int read() throws IOException {
		touch();
		try {
			int b = in.read();
			touch();
			return b;
		} catch (IOException e) {
			throw translate(e);
		}
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		touch();
		try {
			int n = in.read(b, off, len);
			touch();
			return n;
		} catch (IOException e) {
			throw translate(e);
		}
	}

	@Override
	public void close() throws IOException {
		watchdog.cancel(false);
		super.close();
	}

	private void touch() {
		lastActivityNanos = System.nanoTime();
	}

	private IOException translate(IOException e) {
		if (timedOut) {
			var timeout = new IOException("No data received for " + idle + "; aborting");
			timeout.addSuppressed(e);
			return timeout;
		}
		return e;
	}
}
