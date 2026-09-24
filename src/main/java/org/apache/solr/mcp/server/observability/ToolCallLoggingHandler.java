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
package org.apache.solr.mcp.server.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.aop.ObservedAspect.ObservedAspectContext;
import java.util.concurrent.TimeUnit;
import org.aspectj.lang.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Logs one line per MCP tool call: {@code INFO} when it completes, {@code WARN}
 * when it fails.
 *
 * <p>
 * The tool services are {@code @Observed}, so every tool call already runs
 * inside an observation; this handler only reacts to those, and ignores HTTP,
 * security and other observations. The line is written inside the request's
 * trace, so a tool call's trace always has a log line attached in Loki, where
 * the services themselves log only on failure. Spring Boot registers
 * {@code ObservationHandler} beans with the {@code ObservationRegistry}
 * automatically.
 */
@Component
class ToolCallLoggingHandler implements ObservationHandler<ObservedAspectContext> {

	private static final Logger logger = LoggerFactory.getLogger(ToolCallLoggingHandler.class);

	private static final String START_NANOS = ToolCallLoggingHandler.class.getName() + ".startNanos";

	@Override
	public void onStart(ObservedAspectContext context) {
		context.put(START_NANOS, System.nanoTime());
	}

	@Override
	public void onStop(ObservedAspectContext context) {
		Long startNanos = context.get(START_NANOS);
		long millis = startNanos == null ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
		Signature signature = context.getProceedingJoinPoint().getSignature();
		String tool = signature.getDeclaringType().getSimpleName() + "#" + signature.getName();
		Throwable error = context.getError();
		if (error == null) {
			logger.info("{} completed in {} ms", tool, millis);
		} else {
			logger.warn("{} failed after {} ms: {}", tool, millis, error.toString());
		}
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof ObservedAspectContext;
	}
}
