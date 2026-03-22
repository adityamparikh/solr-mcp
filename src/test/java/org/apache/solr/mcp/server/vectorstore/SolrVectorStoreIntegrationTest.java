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
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.SolrContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link SolrVectorStore} using Testcontainers. Uses a
 * deterministic mock embedding model to avoid requiring an API key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({ TestcontainersConfiguration.class, SolrVectorStoreIntegrationTest.TestConfig.class })
@Testcontainers(disabledWithoutDocker = true)
class SolrVectorStoreIntegrationTest {

	private static final String COLLECTION_NAME = "vector_test_" + System.currentTimeMillis();

	private static final int VECTOR_DIMENSION = 4;

	@Autowired
	private SolrContainer solrContainer;

	@Autowired
	private SolrClient solrClient;

	@Autowired
	private VectorStore vectorStore;

	private List<Document> testDocuments;

	@BeforeEach
	void setUp() throws Exception {
		// Create collection idempotently
		try {
			solrContainer.execInContainer("/opt/solr/bin/solr", "create_collection", "-c", COLLECTION_NAME, "-d",
					"_default");
		}
		catch (Exception ignored) {
		}

		// Add vector field type and field
		try {
			String addFieldTypeJson = """
					{
					  "add-field-type": {
					    "name": "knn_vector_%d",
					    "class": "solr.DenseVectorField",
					    "vectorDimension": "%d",
					    "similarityFunction": "cosine",
					    "knnAlgorithm": "hnsw"
					  }
					}
					""".formatted(VECTOR_DIMENSION, VECTOR_DIMENSION);
			String addFieldJson = """
					{
					  "add-field": {
					    "name": "vector",
					    "type": "knn_vector_%d",
					    "indexed": "true",
					    "stored": "true"
					  }
					}
					""".formatted(VECTOR_DIMENSION);
			solrContainer.execInContainer("curl", "-X", "POST",
					"http://localhost:8983/solr/" + COLLECTION_NAME + "/schema", "-H",
					"Content-Type: application/json", "-d", addFieldTypeJson);
			solrContainer.execInContainer("curl", "-X", "POST",
					"http://localhost:8983/solr/" + COLLECTION_NAME + "/schema", "-H",
					"Content-Type: application/json", "-d", addFieldJson);
			Thread.sleep(500);
		}
		catch (Exception ignored) {
		}

		// Clean documents before each test
		try {
			solrClient.deleteByQuery(COLLECTION_NAME, "*:*");
			solrClient.commit(COLLECTION_NAME);
		}
		catch (Exception ignored) {
		}

		// Initialize test documents
		testDocuments = List.of(
				new Document("1", "Spring AI provides abstractions for AI models",
						Map.of("category", "AI", "year", 2024)),
				new Document("2", "Vector databases store embeddings for similarity search",
						Map.of("category", "Database", "year", 2024)),
				new Document("3", "Apache Solr supports dense vector fields for KNN search",
						Map.of("category", "Search", "year", 2023)));
	}

	@Test
	void addAndDeleteDocuments() throws Exception {
		long initialCount = solrClient.query(COLLECTION_NAME, new SolrQuery("*:*")).getResults().getNumFound();
		assertThat(initialCount).isEqualTo(0);

		vectorStore.add(testDocuments);

		await().untilAsserted(() -> {
			long count = solrClient.query(COLLECTION_NAME, new SolrQuery("*:*")).getResults().getNumFound();
			assertThat(count).isEqualTo(3);
		});

		vectorStore.delete(List.of("1", "2", "3"));

		await().untilAsserted(() -> {
			long count = solrClient.query(COLLECTION_NAME, new SolrQuery("*:*")).getResults().getNumFound();
			assertThat(count).isEqualTo(0);
		});
	}

	@Test
	void addAndSearchDocuments() {
		vectorStore.add(testDocuments);

		await().untilAsserted(() -> {
			List<Document> results = vectorStore
					.similaritySearch(SearchRequest.builder().query("AI abstractions").topK(5).build());
			assertThat(results).isNotEmpty();
		});

		List<Document> results = vectorStore
				.similaritySearch(SearchRequest.builder().query("AI abstractions").topK(5).build());

		assertThat(results).hasSizeGreaterThanOrEqualTo(1);
		Document firstResult = results.getFirst();
		assertThat(firstResult.getId()).isNotNull();
		assertThat(firstResult.getText()).isNotNull();
		assertThat(firstResult.getMetadata()).containsKey("score");
	}

	@Test
	void searchWithThreshold() {
		vectorStore.add(testDocuments);

		await().untilAsserted(() -> {
			List<Document> results = vectorStore
					.similaritySearch(SearchRequest.builder().query("AI").topK(5).build());
			assertThat(results).isNotEmpty();
		});

		List<Document> allResults = vectorStore
				.similaritySearch(SearchRequest.builder().query("AI technology").topK(3).build());
		assertThat(allResults).hasSizeGreaterThanOrEqualTo(1);

		// Use a very high threshold to filter out results
		List<Document> filteredResults = vectorStore.similaritySearch(
				SearchRequest.builder().query("AI technology").topK(3).similarityThreshold(0.999).build());

		assertThat(filteredResults.size()).isLessThanOrEqualTo(allResults.size());
	}

	@Test
	void documentUpdate() {
		Document originalDoc = new Document("update-test", "Original content", Map.of("version", 1));
		vectorStore.add(List.of(originalDoc));

		await().untilAsserted(() -> {
			List<Document> results = vectorStore
					.similaritySearch(SearchRequest.builder().query("Original").topK(1).build());
			assertThat(results).hasSize(1);
		});

		Document updatedDoc = new Document("update-test", "Updated content", Map.of("version", 2));
		vectorStore.add(List.of(updatedDoc));

		await().untilAsserted(() -> {
			List<Document> results = vectorStore
					.similaritySearch(SearchRequest.builder().query("Updated").topK(1).build());
			assertThat(results).hasSize(1);
			assertThat(results.getFirst().getText()).isEqualTo("Updated content");
		});
	}

	@Test
	void overDefaultSizeSearch() {
		List<Document> manyDocs = IntStream.range(0, 15)
				.mapToObj(i -> new Document("doc-" + i, "Document number " + i + " about various topics",
						Map.of("index", i)))
				.toList();

		vectorStore.add(manyDocs);

		await().untilAsserted(() -> {
			List<Document> results = vectorStore
					.similaritySearch(SearchRequest.builder().query("topics").topK(15).build());
			assertThat(results).hasSizeGreaterThanOrEqualTo(10);
		});
	}

	@Test
	void getNativeClient() {
		Optional<Object> nativeClient = vectorStore.getNativeClient();
		assertThat(nativeClient).isPresent();
		assertThat(nativeClient.get()).isInstanceOf(SolrClient.class);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		EmbeddingModel mockEmbeddingModel() {
			return new DeterministicEmbeddingModel(VECTOR_DIMENSION);
		}

		@Bean
		@Primary
		VectorStore testVectorStore(SolrClient solrClient, EmbeddingModel embeddingModel) {
			return SolrVectorStore.builder(solrClient, COLLECTION_NAME, embeddingModel)
					.options(SolrVectorStoreOptions.builder().vectorDimension(VECTOR_DIMENSION).build()).build();
		}
	}
}
