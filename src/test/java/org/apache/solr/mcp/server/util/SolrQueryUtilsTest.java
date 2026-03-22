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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SolrQueryUtilsTest {

	@Test
	void testBuildKnnQuery() {
		String result = SolrQueryUtils.buildKnnQuery("vector", 10, "[0.1, 0.2, 0.3]");
		assertThat(result).isEqualTo("{!knn f=vector topK=10}[0.1, 0.2, 0.3]");
	}

	@Test
	void testBuildKnnQueryWithDefaultField() {
		String result = SolrQueryUtils.buildKnnQuery(5, "[0.1, 0.2]");
		assertThat(result).isEqualTo("{!knn f=vector topK=5}[0.1, 0.2]");
	}

	@Test
	void testBuildKnnQueryNullFieldThrows() {
		assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery(null, 10, "[0.1]"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testBuildKnnQueryBlankFieldThrows() {
		assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery("", 10, "[0.1]"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testBuildKnnQueryZeroTopKThrows() {
		assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery("vector", 0, "[0.1]"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testBuildKnnQueryNullVectorThrows() {
		assertThatThrownBy(() -> SolrQueryUtils.buildKnnQuery("vector", 10, null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
