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
package org.apache.solr.mcp.server.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

class SearchModeTest {

	private EmbeddingService createEmbeddingService(boolean configured) {
		@SuppressWarnings("unchecked")
		ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
		if (configured) {
			when(provider.getIfAvailable()).thenReturn(mock(EmbeddingModel.class));
		} else {
			when(provider.getIfAvailable()).thenReturn(null);
		}
		return new EmbeddingService(provider);
	}

	@Test
	void resolveSearchMode_NullMode_WithEmbedding_ReturnsHybrid() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(true));
		assertEquals(SearchMode.HYBRID, service.resolveSearchMode(null));
	}

	@Test
	void resolveSearchMode_NullMode_WithoutEmbedding_ReturnsKeyword() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		assertEquals(SearchMode.KEYWORD, service.resolveSearchMode(null));
	}

	@Test
	void resolveSearchMode_BlankMode_WithoutEmbedding_ReturnsKeyword() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		assertEquals(SearchMode.KEYWORD, service.resolveSearchMode("  "));
	}

	@Test
	void resolveSearchMode_Keyword_ReturnsKeyword() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		assertEquals(SearchMode.KEYWORD, service.resolveSearchMode("keyword"));
	}

	@Test
	void resolveSearchMode_Semantic_CaseInsensitive() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		assertEquals(SearchMode.SEMANTIC, service.resolveSearchMode("SEMANTIC"));
		assertEquals(SearchMode.SEMANTIC, service.resolveSearchMode("semantic"));
		assertEquals(SearchMode.SEMANTIC, service.resolveSearchMode("Semantic"));
	}

	@Test
	void resolveSearchMode_InvalidMode_ThrowsException() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> service.resolveSearchMode("fuzzy"));
		assertTrue(ex.getMessage().contains("fuzzy"));
		assertTrue(ex.getMessage().contains("keyword, semantic, hybrid"));
	}

	@Test
	void vectorToString_ProducesCorrectFormat() {
		float[] embedding = new float[] { 0.1f, 0.2f, 0.3f };
		String result = SearchService.vectorToString(embedding);
		assertTrue(result.startsWith("["));
		assertTrue(result.endsWith("]"));
		assertTrue(result.contains("0.1"));
		assertTrue(result.contains("0.2"));
		assertTrue(result.contains("0.3"));
	}

	@Test
	void vectorToString_EmptyArray() {
		float[] embedding = new float[] {};
		String result = SearchService.vectorToString(embedding);
		assertEquals("[]", result);
	}

	@Test
	void mergeWithRRF_CombinesResults() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));

		// Create keyword results with docs A, B, C
		SearchResponse keywordResults = new SearchResponse(3, 0, 1.0f, List.of(
				Map.of("id", "A", "title", "Doc A"), Map.of("id", "B", "title", "Doc B"),
				Map.of("id", "C", "title", "Doc C")), Map.of());

		// Create semantic results with docs B, D, A
		SearchResponse semanticResults = new SearchResponse(3, 0, 0.9f, List.of(
				Map.of("id", "B", "title", "Doc B"), Map.of("id", "D", "title", "Doc D"),
				Map.of("id", "A", "title", "Doc A")), Map.of());

		SearchResponse merged = service.mergeWithRRF(keywordResults, semanticResults, null, 10);

		// B appears in both lists (rank 2 in keyword, rank 1 in semantic) so should
		// score highest
		assertFalse(merged.documents().isEmpty());
		assertEquals(4, merged.numFound()); // 4 unique docs
		// B should be first (appears in both lists at good positions)
		assertEquals("B", merged.documents().getFirst().get("id"));
	}

	@Test
	void mergeWithRRF_WithPagination() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));

		SearchResponse keywordResults = new SearchResponse(3, 0, 1.0f,
				List.of(Map.of("id", "A"), Map.of("id", "B"), Map.of("id", "C")), Map.of());

		SearchResponse semanticResults = new SearchResponse(2, 0, 0.9f,
				List.of(Map.of("id", "D"), Map.of("id", "E")), Map.of());

		// Get second page (start=2, rows=2)
		SearchResponse merged = service.mergeWithRRF(keywordResults, semanticResults, 2, 2);

		assertEquals(5, merged.numFound());
		assertEquals(2, merged.start());
		assertTrue(merged.documents().size() <= 2);
	}

	@Test
	void search_SemanticWithoutEmbedding_ThrowsException() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		assertThrows(IllegalStateException.class,
				() -> service.search("test", "query", null, null, null, null, null, "semantic", null, null));
	}

	@Test
	void search_HybridWithoutEmbedding_ThrowsException() {
		SearchService service = new SearchService(mock(SolrClient.class), createEmbeddingService(false));
		assertThrows(IllegalStateException.class,
				() -> service.search("test", "query", null, null, null, null, null, "hybrid", null, null));
	}

}
