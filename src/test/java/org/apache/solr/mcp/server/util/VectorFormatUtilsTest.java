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
package org.apache.solr.mcp.server.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class VectorFormatUtilsTest {

	@Test
	void testConvertToList() {
		float[] array = { 0.1f, 0.2f, 0.3f };
		List<Float> result = VectorFormatUtils.convertToList(array);
		assertThat(result).containsExactly(0.1f, 0.2f, 0.3f);
	}

	@Test
	void testConvertToListEmpty() {
		List<Float> result = VectorFormatUtils.convertToList(new float[0]);
		assertThat(result).isEmpty();
	}

	@Test
	void testFormatVectorForSolrFromArray() {
		float[] array = { 0.1f, 0.2f, 0.3f };
		String result = VectorFormatUtils.formatVectorForSolr(array);
		assertThat(result).isEqualTo("[0.1, 0.2, 0.3]");
	}

	@Test
	void testFormatVectorForSolrFromList() {
		List<Float> list = List.of(0.1f, 0.2f, 0.3f);
		String result = VectorFormatUtils.formatVectorForSolr(list);
		assertThat(result).isEqualTo("[0.1, 0.2, 0.3]");
	}

	@Test
	void testFormatVectorForSolrSingleElement() {
		float[] array = { 1.0f };
		String result = VectorFormatUtils.formatVectorForSolr(array);
		assertThat(result).isEqualTo("[1.0]");
	}

	@Test
	void testFormatVectorForSolrEmptyArray() {
		float[] array = {};
		String result = VectorFormatUtils.formatVectorForSolr(array);
		assertThat(result).isEqualTo("[]");
	}
}
