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

import io.micrometer.observation.ObservationRegistry;

import org.apache.solr.client.solrj.SolrClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration for the {@link SolrVectorStore} bean.
 *
 * <p>
 * Creates a {@link VectorStore} bean only when an {@link EmbeddingModel} is
 * available. The collection name is configurable via the
 * {@code solr.default.collection} property.
 */
@Configuration
@ConditionalOnBean(EmbeddingModel.class)
public class VectorStoreConfig {

	@Bean
	public VectorStore solrVectorStore(SolrClient solrClient, EmbeddingModel embeddingModel,
			@Value("${solr.default.collection:documents}") String collectionName,
			ObjectProvider<ObservationRegistry> observationRegistry) {
		SolrVectorStore.Builder builder = SolrVectorStore.builder(solrClient, collectionName, embeddingModel);
		observationRegistry.ifAvailable(builder::observationRegistry);
		return builder.build();
	}

	@Bean
	public VectorStoreFactory vectorStoreFactory(SolrClient solrClient, EmbeddingModel embeddingModel) {
		return new VectorStoreFactory(solrClient, embeddingModel);
	}
}
