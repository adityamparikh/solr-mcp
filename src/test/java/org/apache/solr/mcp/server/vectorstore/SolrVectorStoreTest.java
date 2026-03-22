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
package org.apache.solr.mcp.server.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;

@ExtendWith(MockitoExtension.class)
class SolrVectorStoreTest {

	private static final String COLLECTION = "test_collection";

	@Mock
	private SolrClient solrClient;

	@Mock
	private EmbeddingModel embeddingModel;

	private SolrVectorStore vectorStore;

	@BeforeEach
	void setUp() {
		vectorStore = SolrVectorStore.builder(solrClient, COLLECTION, embeddingModel)
				.options(SolrVectorStoreOptions.defaults()).build();
	}

	@Test
	void testBuilderCreatesInstance() {
		assertThat(vectorStore).isNotNull();
	}

	@Test
	void testBuilderNullSolrClientThrows() {
		assertThatThrownBy(
				() -> SolrVectorStore.builder(null, COLLECTION, embeddingModel).build())
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testBuilderNullCollectionThrows() {
		assertThatThrownBy(
				() -> SolrVectorStore.builder(solrClient, null, embeddingModel).build())
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testAddDocuments() throws Exception {
		float[] embedding = new float[] { 0.1f, 0.2f, 0.3f };
		EmbeddingResponse embeddingResponse = new EmbeddingResponse(
				List.of(new Embedding(embedding, 0)));

		when(embeddingModel.embedForResponse(anyList())).thenReturn(embeddingResponse);
		when(solrClient.add(eq(COLLECTION), anyList(), anyInt())).thenReturn(new UpdateResponse());

		Document doc = new Document("1", "Test content", Map.of("category", "test"));
		vectorStore.add(List.of(doc));

		verify(solrClient).add(eq(COLLECTION), anyList(), eq(1000));
		verify(solrClient).commit(COLLECTION);
	}

	@Test
	void testDeleteDocuments() throws Exception {
		when(solrClient.deleteById(eq(COLLECTION), anyList(), anyInt())).thenReturn(new UpdateResponse());

		vectorStore.delete(List.of("1", "2"));

		verify(solrClient).deleteById(eq(COLLECTION), eq(List.of("1", "2")), eq(1000));
	}

	@Test
	void testSimilaritySearch() throws Exception {
		float[] queryEmbedding = new float[] { 0.1f, 0.2f, 0.3f };
		EmbeddingResponse embeddingResponse = new EmbeddingResponse(
				List.of(new Embedding(queryEmbedding, 0)));
		when(embeddingModel.embedForResponse(anyList())).thenReturn(embeddingResponse);

		SolrDocumentList docs = new SolrDocumentList();
		SolrDocument solrDoc = new SolrDocument();
		solrDoc.addField("id", "1");
		solrDoc.addField("content", "Test content");
		solrDoc.addField("score", 0.95f);
		solrDoc.addField("metadata_category", "test");
		docs.add(solrDoc);
		docs.setNumFound(1);

		QueryResponse queryResponse = org.mockito.Mockito.mock(QueryResponse.class);
		when(queryResponse.getResults()).thenReturn(docs);
		when(solrClient.query(eq(COLLECTION), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
				.thenReturn(queryResponse);

		List<Document> results = vectorStore
				.similaritySearch(SearchRequest.builder().query("test query").topK(5).build());

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().getId()).isEqualTo("1");
		assertThat(results.getFirst().getText()).isEqualTo("Test content");
		assertThat(results.getFirst().getMetadata()).containsEntry("category", "test");
		assertThat(results.getFirst().getMetadata()).containsKey("score");
	}

	@Test
	void testSimilaritySearchWithThreshold() throws Exception {
		float[] queryEmbedding = new float[] { 0.1f, 0.2f, 0.3f };
		EmbeddingResponse embeddingResponse = new EmbeddingResponse(
				List.of(new Embedding(queryEmbedding, 0)));
		when(embeddingModel.embedForResponse(anyList())).thenReturn(embeddingResponse);

		SolrDocumentList docs = new SolrDocumentList();
		SolrDocument highScoreDoc = new SolrDocument();
		highScoreDoc.addField("id", "1");
		highScoreDoc.addField("content", "High relevance");
		highScoreDoc.addField("score", 0.95f);
		docs.add(highScoreDoc);

		SolrDocument lowScoreDoc = new SolrDocument();
		lowScoreDoc.addField("id", "2");
		lowScoreDoc.addField("content", "Low relevance");
		lowScoreDoc.addField("score", 0.3f);
		docs.add(lowScoreDoc);
		docs.setNumFound(2);

		QueryResponse queryResponse = org.mockito.Mockito.mock(QueryResponse.class);
		when(queryResponse.getResults()).thenReturn(docs);
		when(solrClient.query(eq(COLLECTION), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
				.thenReturn(queryResponse);

		List<Document> results = vectorStore.similaritySearch(
				SearchRequest.builder().query("test").topK(5).similarityThreshold(0.5).build());

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().getId()).isEqualTo("1");
	}

	@Test
	void testGetNativeClient() {
		Optional<SolrClient> nativeClient = vectorStore.getNativeClient();
		assertThat(nativeClient).isPresent();
		assertThat(nativeClient.get()).isSameAs(solrClient);
	}

	@Test
	void testConvertFilterEq() {
		Filter.Expression expr = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("category"),
				new Filter.Value("AI"));
		String result = vectorStore.convertFilterToSolrQuery(expr);
		assertThat(result).isEqualTo("metadata_category:\"AI\"");
	}

	@Test
	void testConvertFilterNe() {
		Filter.Expression expr = new Filter.Expression(Filter.ExpressionType.NE, new Filter.Key("category"),
				new Filter.Value("AI"));
		String result = vectorStore.convertFilterToSolrQuery(expr);
		assertThat(result).isEqualTo("-metadata_category:\"AI\"");
	}

	@Test
	void testConvertFilterGt() {
		Filter.Expression expr = new Filter.Expression(Filter.ExpressionType.GT, new Filter.Key("year"),
				new Filter.Value(2023));
		String result = vectorStore.convertFilterToSolrQuery(expr);
		assertThat(result).isEqualTo("metadata_year:{2023 TO *]");
	}

	@Test
	void testConvertFilterGte() {
		Filter.Expression expr = new Filter.Expression(Filter.ExpressionType.GTE, new Filter.Key("year"),
				new Filter.Value(2023));
		String result = vectorStore.convertFilterToSolrQuery(expr);
		assertThat(result).isEqualTo("metadata_year:[2023 TO *]");
	}

	@Test
	void testConvertFilterLt() {
		Filter.Expression expr = new Filter.Expression(Filter.ExpressionType.LT, new Filter.Key("year"),
				new Filter.Value(2025));
		String result = vectorStore.convertFilterToSolrQuery(expr);
		assertThat(result).isEqualTo("metadata_year:[* TO 2025}");
	}

	@Test
	void testConvertFilterLte() {
		Filter.Expression expr = new Filter.Expression(Filter.ExpressionType.LTE, new Filter.Key("year"),
				new Filter.Value(2025));
		String result = vectorStore.convertFilterToSolrQuery(expr);
		assertThat(result).isEqualTo("metadata_year:[* TO 2025]");
	}

	@Test
	void testConvertFilterAnd() {
		Filter.Expression left = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("category"),
				new Filter.Value("AI"));
		Filter.Expression right = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("year"),
				new Filter.Value(2024));
		Filter.Expression and = new Filter.Expression(Filter.ExpressionType.AND, left, right);
		String result = vectorStore.convertFilterToSolrQuery(and);
		assertThat(result).isEqualTo("metadata_category:\"AI\" AND metadata_year:\"2024\"");
	}

	@Test
	void testConvertFilterOr() {
		Filter.Expression left = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("category"),
				new Filter.Value("AI"));
		Filter.Expression right = new Filter.Expression(Filter.ExpressionType.EQ, new Filter.Key("category"),
				new Filter.Value("ML"));
		Filter.Expression or = new Filter.Expression(Filter.ExpressionType.OR, left, right);
		String result = vectorStore.convertFilterToSolrQuery(or);
		assertThat(result).isEqualTo("metadata_category:\"AI\" OR metadata_category:\"ML\"");
	}

	@Test
	void testConvertFilterNull() {
		String result = vectorStore.convertFilterToSolrQuery(null);
		assertThat(result).isNull();
	}

	@Test
	void testOptionsDefaults() {
		SolrVectorStoreOptions defaults = SolrVectorStoreOptions.defaults();
		assertThat(defaults.idFieldName()).isEqualTo("id");
		assertThat(defaults.contentFieldName()).isEqualTo("content");
		assertThat(defaults.vectorFieldName()).isEqualTo("vector");
		assertThat(defaults.metadataPrefix()).isEqualTo("metadata_");
		assertThat(defaults.vectorDimension()).isEqualTo(1536);
	}

	@Test
	void testOptionsBuilder() {
		SolrVectorStoreOptions options = SolrVectorStoreOptions.builder().idFieldName("doc_id")
				.contentFieldName("text").vectorFieldName("embedding").metadataPrefix("meta_").vectorDimension(768)
				.build();
		assertThat(options.idFieldName()).isEqualTo("doc_id");
		assertThat(options.contentFieldName()).isEqualTo("text");
		assertThat(options.vectorFieldName()).isEqualTo("embedding");
		assertThat(options.metadataPrefix()).isEqualTo("meta_");
		assertThat(options.vectorDimension()).isEqualTo(768);
	}

	@Test
	void testOptionsNullFallbackToDefaults() {
		SolrVectorStoreOptions options = new SolrVectorStoreOptions(null, null, null, null, 0);
		assertThat(options.idFieldName()).isEqualTo("id");
		assertThat(options.contentFieldName()).isEqualTo("content");
		assertThat(options.vectorFieldName()).isEqualTo("vector");
		assertThat(options.metadataPrefix()).isEqualTo("metadata_");
		assertThat(options.vectorDimension()).isEqualTo(1536);
	}

	@Test
	void testMultiValuedFieldHandling() throws Exception {
		float[] queryEmbedding = new float[] { 0.1f, 0.2f, 0.3f };
		EmbeddingResponse embeddingResponse = new EmbeddingResponse(
				List.of(new Embedding(queryEmbedding, 0)));
		when(embeddingModel.embedForResponse(anyList())).thenReturn(embeddingResponse);

		SolrDocumentList docs = new SolrDocumentList();
		SolrDocument solrDoc = new SolrDocument();
		solrDoc.addField("id", List.of("1"));
		solrDoc.addField("content", List.of("Test content"));
		solrDoc.addField("score", 0.9f);
		solrDoc.addField("metadata_category", List.of("test"));
		docs.add(solrDoc);
		docs.setNumFound(1);

		QueryResponse queryResponse = org.mockito.Mockito.mock(QueryResponse.class);
		when(queryResponse.getResults()).thenReturn(docs);
		when(solrClient.query(eq(COLLECTION), any(SolrQuery.class), eq(SolrRequest.METHOD.POST)))
				.thenReturn(queryResponse);

		List<Document> results = vectorStore
				.similaritySearch(SearchRequest.builder().query("test").topK(5).build());

		assertThat(results).hasSize(1);
		assertThat(results.getFirst().getId()).isEqualTo("1");
		assertThat(results.getFirst().getText()).isEqualTo("Test content");
		assertThat(results.getFirst().getMetadata()).containsEntry("category", "test");
	}

	@Test
	void testObservationContextBuilder() {
		var contextBuilder = vectorStore.createObservationContextBuilder("query");
		assertThat(contextBuilder).isNotNull();
	}
}
