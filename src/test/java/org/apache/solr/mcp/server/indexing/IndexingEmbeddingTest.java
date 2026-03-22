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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.search.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IndexingEmbeddingTest {

	@Mock
	private EmbeddingService embeddingService;

	@Test
	void addEmbeddingsIfRequested_WhenFalse_NoEmbeddingsGenerated() {
		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", "1");
		doc.addField("title", "Test");
		docs.add(doc);

		indexingService.addEmbeddingsIfRequested(docs, false, List.of("title"));

		assertNull(doc.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		verifyNoInteractions(embeddingService);
	}

	@Test
	void addEmbeddingsIfRequested_WhenNull_NoEmbeddingsGenerated() {
		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", "1");
		docs.add(doc);

		indexingService.addEmbeddingsIfRequested(docs, null, null);

		assertNull(doc.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		verifyNoInteractions(embeddingService);
	}

	@Test
	void addEmbeddingsIfRequested_WithNoEmbeddingModel_ThrowsException() {
		when(embeddingService.isConfigured()).thenReturn(false);
		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", "1");
		docs.add(doc);

		assertThrows(IllegalStateException.class,
				() -> indexingService.addEmbeddingsIfRequested(docs, true, List.of("title")));
	}

	@Test
	void addEmbeddingsIfRequested_WithEmptyTextFields_ThrowsException() {
		when(embeddingService.isConfigured()).thenReturn(true);
		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", "1");
		docs.add(doc);

		assertThrows(IllegalArgumentException.class,
				() -> indexingService.addEmbeddingsIfRequested(docs, true, List.of()));
	}

	@Test
	void addEmbeddingsIfRequested_GeneratesEmbeddings() {
		when(embeddingService.isConfigured()).thenReturn(true);
		float[] embedding = new float[] { 0.1f, 0.2f, 0.3f };
		when(embeddingService.embed("Hello World")).thenReturn(embedding);

		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", "1");
		doc.addField("title", "Hello");
		doc.addField("body", "World");
		docs.add(doc);

		indexingService.addEmbeddingsIfRequested(docs, true, List.of("title", "body"));

		assertNotNull(doc.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		verify(embeddingService).embed("Hello World");
	}

	@Test
	void addEmbeddingsIfRequested_SkipsDocWithMissingFields() {
		when(embeddingService.isConfigured()).thenReturn(true);

		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();
		SolrInputDocument doc = new SolrInputDocument();
		doc.addField("id", "1");
		// No title or body fields
		docs.add(doc);

		indexingService.addEmbeddingsIfRequested(docs, true, List.of("title", "body"));

		assertNull(doc.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		verify(embeddingService, never()).embed(anyString());
	}

	@Test
	void addEmbeddingsIfRequested_MultipleDocsSomeSkipped() {
		when(embeddingService.isConfigured()).thenReturn(true);
		float[] embedding = new float[] { 0.1f, 0.2f };
		when(embeddingService.embed(anyString())).thenReturn(embedding);

		IndexingService indexingService = new IndexingService(null, null, embeddingService);

		List<SolrInputDocument> docs = new ArrayList<>();

		SolrInputDocument doc1 = new SolrInputDocument();
		doc1.addField("id", "1");
		doc1.addField("title", "Has title");
		docs.add(doc1);

		SolrInputDocument doc2 = new SolrInputDocument();
		doc2.addField("id", "2");
		// No title field
		docs.add(doc2);

		SolrInputDocument doc3 = new SolrInputDocument();
		doc3.addField("id", "3");
		doc3.addField("title", "Also has title");
		docs.add(doc3);

		indexingService.addEmbeddingsIfRequested(docs, true, List.of("title"));

		// doc1 and doc3 should have embeddings, doc2 should not
		assertNotNull(doc1.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		assertNull(doc2.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		assertNotNull(doc3.getFieldValue(IndexingService.DEFAULT_VECTOR_FIELD));
		verify(embeddingService, times(2)).embed(anyString());
	}

}
