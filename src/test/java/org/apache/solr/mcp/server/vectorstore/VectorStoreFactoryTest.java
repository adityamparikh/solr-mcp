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
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

@ExtendWith(MockitoExtension.class)
class VectorStoreFactoryTest {

	@Mock
	private SolrClient solrClient;

	@Mock
	private EmbeddingModel embeddingModel;

	private VectorStoreFactory factory;

	@BeforeEach
	void setUp() {
		factory = new VectorStoreFactory(solrClient, embeddingModel);
	}

	@Test
	void testCreateVectorStore() {
		VectorStore vectorStore = factory.forCollection("testCollection");
		assertThat(vectorStore).isNotNull();
		assertThat(vectorStore).isInstanceOf(SolrVectorStore.class);
	}

	@Test
	void testCacheReturnsSameInstance() {
		VectorStore store1 = factory.forCollection("testCollection");
		VectorStore store2 = factory.forCollection("testCollection");
		assertThat(store1).isSameAs(store2);
	}

	@Test
	void testDifferentCollectionsGetDifferentInstances() {
		VectorStore store1 = factory.forCollection("collection1");
		VectorStore store2 = factory.forCollection("collection2");
		assertThat(store1).isNotSameAs(store2);
	}

	@Test
	void testNullCollectionHandling() {
		assertThatThrownBy(() -> factory.forCollection(null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Collection name cannot be null or blank");
	}

	@Test
	void testBlankCollectionName() {
		assertThatThrownBy(() -> factory.forCollection("")).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Collection name cannot be null or blank");

		assertThatThrownBy(() -> factory.forCollection("   ")).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Collection name cannot be null or blank");
	}

	@Test
	void testThreadSafety() throws InterruptedException {
		int threadCount = 10;
		int iterationsPerThread = 100;
		String collection = "testCollection";

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch endLatch = new CountDownLatch(threadCount);
		AtomicInteger instanceCount = new AtomicInteger(0);
		VectorStore[] firstInstance = new VectorStore[1];

		for (int i = 0; i < threadCount; i++) {
			executor.submit(() -> {
				try {
					startLatch.await();
					for (int j = 0; j < iterationsPerThread; j++) {
						VectorStore store = factory.forCollection(collection);
						if (firstInstance[0] == null) {
							synchronized (firstInstance) {
								if (firstInstance[0] == null) {
									firstInstance[0] = store;
									instanceCount.incrementAndGet();
								}
							}
						}
						assertThat(store).isSameAs(firstInstance[0]);
					}
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
		assertThat(instanceCount.get()).isEqualTo(1);
		executor.shutdown();
	}

	@Test
	void testMultipleCollectionsConcurrent() throws InterruptedException {
		int threadCount = 10;
		String[] collections = { "col1", "col2", "col3", "col4", "col5" };

		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch startLatch = new CountDownLatch(1);
		CountDownLatch endLatch = new CountDownLatch(threadCount);

		for (int i = 0; i < threadCount; i++) {
			final int threadId = i;
			executor.submit(() -> {
				try {
					startLatch.await();
					String collection = collections[threadId % collections.length];
					VectorStore store1 = factory.forCollection(collection);
					VectorStore store2 = factory.forCollection(collection);
					assertThat(store1).isSameAs(store2);
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
		executor.shutdown();
	}

	@Test
	void testSpecialCharactersInCollectionName() {
		String[] specialCollections = { "test-collection", "test_collection", "test.collection", "test123",
				"TEST_COLLECTION" };

		for (String collection : specialCollections) {
			VectorStore store = factory.forCollection(collection);
			assertThat(store).isNotNull();
			VectorStore cachedStore = factory.forCollection(collection);
			assertThat(cachedStore).isSameAs(store);
		}
	}

	@Test
	void testClearCache() {
		VectorStore store1 = factory.forCollection("collection1");
		factory.forCollection("collection2");
		factory.forCollection("collection3");
		assertThat(factory.getCacheSize()).isEqualTo(3);

		factory.clearCache();
		assertThat(factory.getCacheSize()).isEqualTo(0);

		VectorStore newStore1 = factory.forCollection("collection1");
		assertThat(newStore1).isNotNull();
		assertThat(newStore1).isNotSameAs(store1);
		assertThat(factory.getCacheSize()).isEqualTo(1);
	}

	@Test
	void testEvictSpecificCollection() {
		VectorStore store1 = factory.forCollection("collection1");
		VectorStore store2 = factory.forCollection("collection2");
		VectorStore store3 = factory.forCollection("collection3");
		assertThat(factory.getCacheSize()).isEqualTo(3);

		VectorStore evictedStore = factory.evict("collection2");
		assertThat(evictedStore).isSameAs(store2);
		assertThat(factory.getCacheSize()).isEqualTo(2);

		assertThat(factory.forCollection("collection1")).isSameAs(store1);
		assertThat(factory.forCollection("collection3")).isSameAs(store3);

		VectorStore newStore2 = factory.forCollection("collection2");
		assertThat(newStore2).isNotNull();
		assertThat(newStore2).isNotSameAs(store2);
	}

	@Test
	void testEvictNonExistentCollection() {
		factory.forCollection("existing");
		assertThat(factory.getCacheSize()).isEqualTo(1);

		VectorStore result = factory.evict("nonexistent");
		assertThat(result).isNull();
		assertThat(factory.getCacheSize()).isEqualTo(1);
	}

	@Test
	void testGetCacheSize() {
		assertThat(factory.getCacheSize()).isEqualTo(0);

		factory.forCollection("col1");
		assertThat(factory.getCacheSize()).isEqualTo(1);

		factory.forCollection("col2");
		assertThat(factory.getCacheSize()).isEqualTo(2);

		factory.forCollection("col1");
		assertThat(factory.getCacheSize()).isEqualTo(2);

		factory.evict("col2");
		assertThat(factory.getCacheSize()).isEqualTo(1);

		factory.clearCache();
		assertThat(factory.getCacheSize()).isEqualTo(0);
	}
}
