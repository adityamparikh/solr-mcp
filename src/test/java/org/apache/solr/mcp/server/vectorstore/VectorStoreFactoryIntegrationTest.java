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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link VectorStoreFactory} using Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({ TestcontainersConfiguration.class, VectorStoreFactoryIntegrationTest.TestConfig.class })
@Testcontainers(disabledWithoutDocker = true)
class VectorStoreFactoryIntegrationTest {

	@Autowired
	private SolrClient solrClient;

	@Autowired
	private EmbeddingModel embeddingModel;

	private VectorStoreFactory factory;

	@BeforeEach
	void setUp() {
		factory = new VectorStoreFactory(solrClient, embeddingModel);
	}

	@Test
	void testCreateVectorStoreForCollection() {
		VectorStore store = factory.forCollection("test_collection");
		assertThat(store).isNotNull();
		assertThat(store).isInstanceOf(SolrVectorStore.class);
	}

	@Test
	void testCachingBehavior() {
		VectorStore store1 = factory.forCollection("collection_a");
		VectorStore store2 = factory.forCollection("collection_a");
		assertThat(store1).isSameAs(store2);
		assertThat(factory.getCacheSize()).isEqualTo(1);
	}

	@Test
	void testDifferentCollections() {
		VectorStore storeA = factory.forCollection("collection_a");
		VectorStore storeB = factory.forCollection("collection_b");
		assertThat(storeA).isNotSameAs(storeB);
		assertThat(factory.getCacheSize()).isEqualTo(2);
	}

	@Test
	void testEvictAndRecreate() {
		VectorStore original = factory.forCollection("evict_test");
		factory.evict("evict_test");
		assertThat(factory.getCacheSize()).isEqualTo(0);

		VectorStore recreated = factory.forCollection("evict_test");
		assertThat(recreated).isNotSameAs(original);
	}

	@Test
	void testClearCache() {
		factory.forCollection("c1");
		factory.forCollection("c2");
		factory.forCollection("c3");
		assertThat(factory.getCacheSize()).isEqualTo(3);

		factory.clearCache();
		assertThat(factory.getCacheSize()).isEqualTo(0);
	}

	@Test
	void testNullCollectionThrows() {
		assertThatThrownBy(() -> factory.forCollection(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void testConcurrentAccess() throws InterruptedException {
		int threadCount = 10;
		String[] collections = { "col1", "col2", "col3" };

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch endLatch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			final int idx = i;
			executor.submit(() -> {
				try {
					startLatch.await();
					String collection = collections[idx % collections.length];
					VectorStore s1 = factory.forCollection(collection);
					VectorStore s2 = factory.forCollection(collection);
					assertThat(s1).isSameAs(s2);
				}
				catch (Exception e) {
					throw new RuntimeException(e);
				}
				finally {
					endLatch.countDown();
				}
			});
		}

		startLatch.countDown();
		boolean completed = endLatch.await(10, TimeUnit.SECONDS);
		assertThat(completed).isTrue();
		assertThat(factory.getCacheSize()).isEqualTo(3);
		executor.shutdown();
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		EmbeddingModel mockEmbeddingModel() {
			return new DeterministicEmbeddingModel(4);
		}
	}
}
