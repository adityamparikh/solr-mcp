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
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Logs one line per MCP tool call: {@code INFO} when it completes, {@code WARN}
 * when it fails.
 *
 * <p>
 * The tool services are {@code @Observed}, so every tool call already runs
 * inside an observation. {@code @Observed} sits on the service classes, which
 * also hold resource, prompt and completion handlers; this handler reacts only
 * to {@code @McpTool} methods, and ignores those, HTTP, security and other
 * observations. The line names the call by the observation's contextual name
 * ({@code SearchService#search}) and is written inside the request's trace, so
 * a tool call's trace always has a log line attached in Loki, where the
 * services themselves log only on failure. A failure logs the exception type
 * only: the message can carry Solr's HTML error page, and already goes back to
 * the client as the tool result. Spring Boot registers
 * {@code ObservationHandler} beans with the {@code ObservationRegistry}
 * automatically.
 *
 * <p>
 * Active only in the {@code http} profile, where {@code @Observed} observations
 * are enabled and logs are exported; STDIO mode has no log appenders.
 */
@Component
@Profile("http")
class ToolCallLoggingHandler implements ObservationHandler<ObservedAspectContext> {

	private static final Logger logger = LoggerFactory.getLogger(ToolCallLoggingHandler.class);

	private static final String START_NANOS = ToolCallLoggingHandler.class.getName() + ".startNanos";

	@Override
	public void onStart(ObservedAspectContext context) {
		context.put(START_NANOS, System.nanoTime());
	}

	@Override
	public void onStop(ObservedAspectContext context) {
		long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - context.<Long>getRequired(START_NANOS));
		Throwable error = context.getError();
		if (error == null) {
			logger.info("{} completed in {} ms", context.getContextualName(), millis);
		} else {
			logger.warn("{} failed after {} ms: {}", context.getContextualName(), millis, error.getClass().getName());
		}
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof ObservedAspectContext observed
				&& observed.getProceedingJoinPoint().getSignature() instanceof MethodSignature signature
				&& signature.getMethod().isAnnotationPresent(McpTool.class);
	}
}
