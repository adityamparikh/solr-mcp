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

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Service that wraps an optional {@link EmbeddingModel} for generating vector
 * embeddings from text.
 *
 * <p>
 * Uses {@link ObjectProvider} to make the embedding model dependency optional.
 * When no embedding model is configured, the server operates in keyword-only
 * mode.
 */
@Service
public class EmbeddingService {

	private final EmbeddingModel embeddingModel;

	public EmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
		this.embeddingModel = embeddingModelProvider.getIfAvailable();
	}

	/**
	 * Returns whether an embedding model is configured and available.
	 */
	public boolean isConfigured() {
		return embeddingModel != null;
	}

	/**
	 * Generates a vector embedding for the given text.
	 *
	 * @param text
	 *            the text to embed
	 * @return the embedding as a float array
	 * @throws IllegalStateException
	 *             if no embedding model is configured
	 */
	public float[] embed(String text) {
		if (embeddingModel == null) {
			throw new IllegalStateException(
					"Embedding model is not configured. Semantic and hybrid search require an embedding model.");
		}
		return embeddingModel.embed(text);
	}

}
