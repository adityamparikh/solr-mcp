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

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

class EmbeddingServiceTest {

	@Test
	void isConfigured_WithNoModel_ReturnsFalse() {
		@SuppressWarnings("unchecked")
		ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);

		EmbeddingService service = new EmbeddingService(provider);
		assertFalse(service.isConfigured());
	}

	@Test
	void isConfigured_WithModel_ReturnsTrue() {
		EmbeddingModel model = mock(EmbeddingModel.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(model);

		EmbeddingService service = new EmbeddingService(provider);
		assertTrue(service.isConfigured());
	}

	@Test
	void embed_WithNoModel_ThrowsIllegalStateException() {
		@SuppressWarnings("unchecked")
		ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(null);

		EmbeddingService service = new EmbeddingService(provider);
		assertThrows(IllegalStateException.class, () -> service.embed("test text"));
	}

	@Test
	void embed_WithModel_ReturnsEmbedding() {
		EmbeddingModel model = mock(EmbeddingModel.class);
		float[] expectedEmbedding = new float[] { 0.1f, 0.2f, 0.3f };
		when(model.embed("test text")).thenReturn(expectedEmbedding);

		@SuppressWarnings("unchecked")
		ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(model);

		EmbeddingService service = new EmbeddingService(provider);
		float[] result = service.embed("test text");
		assertArrayEquals(expectedEmbedding, result);
	}

}
