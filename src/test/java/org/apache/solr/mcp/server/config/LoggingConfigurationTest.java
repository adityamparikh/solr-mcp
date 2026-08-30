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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * Guards the logging configuration against the file-shadowing trap described in
 * Spring Boot's reference documentation.
 *
 * <p>
 * Spring Boot resolves a Logback configuration in
 * {@code AbstractLoggingSystem.initializeWithConventions()}. That method looks
 * at the <em>standard</em> Logback locations first — {@code logback-test.xml},
 * {@code logback.xml} and their Groovy variants. If one is found and no
 * {@code logging.file.name} is set, Boot reinitializes from that file and
 * returns, so {@code logback-spring.xml} is never consulted at all.
 *
 * <p>
 * Shipping both files is therefore not "one overrides the other" — it silently
 * disables the {@code -spring} file. In this application that meant the
 * {@code http} profile lost its {@code CONSOLE} and {@code OTEL} appenders,
 * which live in {@code logback-spring.xml} inside a {@code <springProfile>}
 * block: HTTP mode emitted no log output whatsoever, and a failed startup
 * exited 1 with nothing but the Spring banner.
 *
 * <p>
 * Boot's reference documentation states the rule directly: <em>"We recommend
 * that you use the -spring variant for your logging configuration (for example,
 * logback-spring.xml rather than logback.xml). If you use standard
 * configuration locations, Spring cannot completely control log
 * initialization."</em> The {@code <springProfile>} extension in particular
 * <em>"cannot be used in the standard logback.xml file because it is loaded too
 * early"</em>.
 *
 * @see <a href=
 *      "https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.custom-log-configuration">Spring
 *      Boot — Custom Log Configuration</a>
 */
class LoggingConfigurationTest {

	private static final String[] STANDARD_LOGBACK_LOCATIONS = {"logback-test.xml", "logback-test.groovy",
			"logback.groovy", "logback.xml"};

	/**
	 * The {@code -spring} variant must be the configuration Spring Boot actually
	 * loads, which requires that no standard-location file shadows it.
	 */
	@Test
	void springVariantIsNotShadowedByAStandardLogbackConfiguration() {
		ClassLoader classLoader = getClass().getClassLoader();

		for (String location : STANDARD_LOGBACK_LOCATIONS) {
			URL shadowing = classLoader.getResource(location);
			assertThat(shadowing)
					.as("%s is on the classpath and shadows logback-spring.xml: Spring Boot resolves it first "
							+ "in AbstractLoggingSystem.initializeWithConventions() and never loads the -spring "
							+ "variant, so every <springProfile> appender is silently dropped", location)
					.isNull();
		}
	}

	/** The configuration Spring Boot is expected to load must exist. */
	@Test
	void springVariantIsPresentOnTheClasspath() {
		assertThat(getClass().getClassLoader().getResource("logback-spring.xml"))
				.as("logback-spring.xml carries the per-profile appenders and must ship on the classpath").isNotNull();
	}
}
