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
package org.apache.solr.mcp.server.indexing.documentcreator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link FieldNameSanitizer}.
 */
class FieldNameSanitizerTest {

	@ParameterizedTest
	@CsvSource({"id, id", "Product Name, product_name", "price$USD, price_usd", "  spaced  , spaced",
			"__leading_and_trailing__, leading_and_trailing", "a---b, a_b", "2024_total, field_2024_total",
			"'!!!', field", "'', field"})
	void sanitizeFieldName_ShouldProduceSolrCompatibleNames(String input, String expected) {
		assertEquals(expected, FieldNameSanitizer.sanitizeFieldName(input));
	}

	/**
	 * The default locale must not influence the result.
	 *
	 * <p>
	 * Under a Turkish locale {@code "ID".toLowerCase()} produces the dotless
	 * {@code "ıd"}. That dotless i is not an ASCII word character, so the invalid
	 * character pattern replaces it with an underscore, which is then stripped as a
	 * leading underscore - yielding the field {@code "d"} instead of {@code "id"}.
	 * A document indexed on a Turkish-locale JVM would therefore land in different
	 * fields from the same document indexed anywhere else.
	 */
	@Test
	void sanitizeFieldName_UnderTurkishDefaultLocale_ShouldStillLowercaseAsAscii() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("tr"));

			assertEquals("id", FieldNameSanitizer.sanitizeFieldName("ID"));
			assertEquals("title", FieldNameSanitizer.sanitizeFieldName("TITLE"));
			assertEquals("is_in_stock", FieldNameSanitizer.sanitizeFieldName("IS_IN_STOCK"));
		} finally {
			Locale.setDefault(original);
		}
	}
}
