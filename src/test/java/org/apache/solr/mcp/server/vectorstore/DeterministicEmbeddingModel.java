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

import java.util.List;

import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A deterministic embedding model for testing that generates embeddings based on
 * the hash of the input text. Produces consistent embeddings for the same input
 * text, allowing similarity search to work deterministically in tests.
 */
class DeterministicEmbeddingModel implements EmbeddingModel {

	private final int dimension;

	DeterministicEmbeddingModel(int dimension) {
		this.dimension = dimension;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<Embedding> embeddings = request.getInstructions().stream()
				.map(instruction -> new Embedding(generateEmbedding(instruction), 0)).toList();
		return new EmbeddingResponse(embeddings);
	}

	@Override
	public float[] embed(String text) {
		return generateEmbedding(text);
	}

	private float[] generateEmbedding(String text) {
		float[] embedding = new float[dimension];
		int hash = text.hashCode();
		for (int i = 0; i < dimension; i++) {
			// Generate deterministic values based on hash and position
			embedding[i] = (float) Math.sin(hash * (i + 1) * 0.1);
		}
		// Normalize the vector to unit length for cosine similarity
		float norm = 0;
		for (float v : embedding) {
			norm += v * v;
		}
		norm = (float) Math.sqrt(norm);
		if (norm > 0) {
			for (int i = 0; i < dimension; i++) {
				embedding[i] /= norm;
			}
		}
		return embedding;
	}
}
