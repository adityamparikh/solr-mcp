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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * Factory for creating and caching {@link VectorStore} instances per Solr
 * collection.
 *
 * <p>
 * Maintains a cache using {@link ConcurrentHashMap} for high-performance
 * concurrent access. Suitable for most use cases where the number of
 * collections is reasonably bounded.
 */
public class VectorStoreFactory {

	private final SolrClient solrClient;

	private final EmbeddingModel embeddingModel;

	private final Map<String, VectorStore> cache;

	public VectorStoreFactory(SolrClient solrClient, EmbeddingModel embeddingModel) {
		this.solrClient = solrClient;
		this.embeddingModel = embeddingModel;
		this.cache = new ConcurrentHashMap<>();
	}

	/**
	 * Returns a VectorStore instance bound to the given collection. Instances are
	 * cached for reuse.
	 *
	 * @param collection
	 *            the name of the Solr collection
	 * @return a VectorStore instance for the specified collection
	 * @throws IllegalArgumentException
	 *             if collection is null or blank
	 */
	public VectorStore forCollection(String collection) {
		if (collection == null || collection.trim().isEmpty()) {
			throw new IllegalArgumentException("Collection name cannot be null or blank");
		}
		return cache.computeIfAbsent(collection,
				c -> SolrVectorStore.builder(solrClient, c, embeddingModel).build());
	}

	/**
	 * Returns the current size of the cache.
	 */
	public int getCacheSize() {
		return cache.size();
	}

	/**
	 * Clears the cache, removing all cached VectorStore instances.
	 */
	public void clearCache() {
		cache.clear();
	}

	/**
	 * Removes a specific collection from the cache.
	 *
	 * @param collection
	 *            the name of the collection to remove
	 * @return the removed VectorStore instance, or null if not cached
	 */
	public VectorStore evict(String collection) {
		return cache.remove(collection);
	}

	/**
	 * Returns the embedding model used by this factory.
	 */
	public EmbeddingModel getEmbeddingModel() {
		return embeddingModel;
	}
}
