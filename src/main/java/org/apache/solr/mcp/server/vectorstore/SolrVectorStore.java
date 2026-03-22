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

import static java.util.stream.Collectors.toList;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.client.solrj.response.schema.SchemaResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.util.SolrQueryUtils;
import org.apache.solr.mcp.server.util.VectorFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.AbstractVectorStoreBuilder;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.observation.AbstractObservationVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;
import org.springframework.util.Assert;

/**
 * Apache Solr vector store implementation for Spring AI document storage and
 * retrieval.
 *
 * <p>
 * Provides a full-featured vector store using Apache Solr's dense vector search
 * capabilities (Solr 9.0+). Enables semantic search by storing document
 * embeddings and performing similarity-based retrieval using KNN.
 *
 * <p>
 * Key features:
 * <ul>
 * <li>Automatic embedding generation via a configurable
 * {@link EmbeddingModel}</li>
 * <li>HNSW algorithm for efficient similarity search</li>
 * <li>Cosine similarity metric for semantic matching</li>
 * <li>Metadata filtering with Spring AI filter expressions</li>
 * <li>Observability support via Micrometer metrics</li>
 * <li>Batch document processing for improved performance</li>
 * </ul>
 *
 * <h3>Solr Schema Requirements:</h3>
 *
 * <pre>{@code
 * <field name="id" type="string" indexed="true" stored="true" required="true"/>
 * <field name="content" type="text_general" indexed="true" stored="true"/>
 * <field name="vector" type="knn_vector_1536" indexed="true" stored="true"/>
 *
 * <fieldType name="knn_vector_1536" class="solr.DenseVectorField"
 *            vectorDimension="1536"
 *            similarityFunction="cosine"
 *            knnAlgorithm="hnsw"/>
 * }</pre>
 *
 * <h3>Usage Example:</h3>
 *
 * <pre>{@code
 * SolrVectorStore vectorStore = SolrVectorStore
 *         .builder(solrClient, "products", embeddingModel)
 *         .options(SolrVectorStoreOptions.defaults())
 *         .build();
 *
 * // Add documents
 * vectorStore.add(List.of(
 *         Document.builder().id("1").text("Running shoes").build()));
 *
 * // Semantic search
 * List<Document> results = vectorStore.similaritySearch(
 *         SearchRequest.builder().query("athletic footwear").topK(10).build());
 * }</pre>
 *
 * @see AbstractObservationVectorStore
 * @see SolrVectorStoreOptions
 */
public class SolrVectorStore extends AbstractObservationVectorStore {

	private static final Logger log = LoggerFactory.getLogger(SolrVectorStore.class);

	private final SolrClient solrClient;

	private final String collection;

	private final SolrVectorStoreOptions options;

	private final boolean initializeSchema;

	/**
	 * Cached result of vector field detection. {@code null} means not yet checked,
	 * {@code Boolean.TRUE/FALSE} means checked.
	 */
	private final AtomicReference<Boolean> vectorFieldAvailable = new AtomicReference<>();

	protected SolrVectorStore(Builder builder) {
		super(builder);

		Assert.notNull(builder.solrClient, "SolrClient must not be null");
		Assert.notNull(builder.collection, "Collection name must not be null");

		this.solrClient = builder.solrClient;
		this.collection = builder.collection;
		this.options = builder.options != null ? builder.options : SolrVectorStoreOptions.defaults();
		this.initializeSchema = builder.initializeSchema;

		// Allow pre-setting the vector field availability (e.g., from tests or when
		// the caller already knows the schema)
		if (builder.forceVectorFieldAvailable != null) {
			this.vectorFieldAvailable.set(builder.forceVectorFieldAvailable);
		}

		if (this.initializeSchema) {
			initializeSchema();
		}
	}

	/**
	 * Creates a builder for SolrVectorStore.
	 *
	 * @param solrClient
	 *            the Solr client
	 * @param collection
	 *            the Solr collection name
	 * @param embeddingModel
	 *            the embedding model
	 * @return builder instance
	 */
	public static Builder builder(SolrClient solrClient, String collection, EmbeddingModel embeddingModel) {
		return new Builder(solrClient, collection, embeddingModel);
	}

	@Override
	public void doAdd(List<Document> documents) {
		try {
			boolean vectorEnabled = hasVectorField();

			if (vectorEnabled) {
				List<Document> documentsWithoutEmbeddings = documents.stream().filter(doc -> {
					Object embedding = doc.getMetadata().get("embedding");
					return embedding == null || !(embedding instanceof float[])
							|| ((float[]) embedding).length == 0;
				}).toList();

				if (!documentsWithoutEmbeddings.isEmpty()) {
					List<String> texts = documentsWithoutEmbeddings.stream().map(Document::getText).toList();

					EmbeddingResponse embeddingResponse = this.embeddingModel.embedForResponse(texts);

					if (embeddingResponse.getResults().size() != documentsWithoutEmbeddings.size()) {
						throw new RuntimeException("Expected " + documentsWithoutEmbeddings.size()
								+ " embeddings but got " + embeddingResponse.getResults().size());
					}

					for (int i = 0; i < documentsWithoutEmbeddings.size(); i++) {
						Document doc = documentsWithoutEmbeddings.get(i);
						float[] embedding = embeddingResponse.getResults().get(i).getOutput();
						doc.getMetadata().put("embedding", embedding);
					}
				}
			}
			else {
				log.debug("No vector field in collection '{}' — indexing without embeddings", collection);
			}

			List<SolrInputDocument> solrDocs = documents.stream().map(this::toSolrDocument).collect(toList());

			UpdateResponse response = solrClient.add(collection, solrDocs, 1000);
			solrClient.commit(collection);

			log.debug("Added {} documents to Solr collection '{}', status: {}", documents.size(), collection,
					response.getStatus());
		}
		catch (SolrServerException | IOException e) {
			throw new RuntimeException("Failed to add documents to Solr", e);
		}
	}

	@Override
	public void doDelete(List<String> idList) {
		try {
			UpdateResponse response = solrClient.deleteById(collection, idList, 1000);

			log.debug("Deleted {} documents from Solr collection '{}', status: {}", idList.size(), collection,
					response.getStatus());
		}
		catch (SolrServerException | IOException e) {
			throw new RuntimeException("Failed to delete documents from Solr", e);
		}
	}

	@Override
	public List<Document> doSimilaritySearch(SearchRequest request) {
		try {
			if (request.getQuery().isEmpty()) {
				throw new IllegalArgumentException("Search query cannot be null or empty");
			}

			if (!hasVectorField()) {
				return executeKeywordFallback(request);
			}

			EmbeddingResponse embeddingResponse = this.embeddingModel
					.embedForResponse(Collections.singletonList(request.getQuery()));

			if (embeddingResponse.getResults().isEmpty()) {
				throw new RuntimeException("Failed to generate embedding for query");
			}

			float[] queryEmbedding = embeddingResponse.getResults().getFirst().getOutput();

			String vectorString = VectorFormatUtils.formatVectorForSolr(queryEmbedding);
			String knnQuery = SolrQueryUtils.buildKnnQuery(options.vectorFieldName(), request.getTopK(),
					vectorString);

			SolrQuery query = new SolrQuery(knnQuery);
			if (request.getTopK() > 0) {
				query.setRows(request.getTopK());
			}

			applyFiltersAndFields(query, request);

			query.setParam("qt", "/select");
			QueryResponse response = solrClient.query(collection, query, SolrRequest.METHOD.POST);
			List<SolrDocument> results = response.getResults();

			log.debug("Similarity search in collection '{}' returned {} results", collection, results.size());

			double threshold = request.getSimilarityThreshold();
			return results.stream().map(solrDoc -> toDocument(solrDoc, threshold)).filter(Objects::nonNull)
					.filter(doc -> {
						if (threshold >= 0) {
							Object scoreObj = doc.getMetadata().get("score");
							if (scoreObj instanceof Number) {
								double score = ((Number) scoreObj).doubleValue();
								return score >= threshold;
							}
							return false;
						}
						return true;
					}).toList();
		}
		catch (SolrServerException | IOException e) {
			throw new RuntimeException("Failed to perform similarity search in Solr", e);
		}
	}

	/**
	 * Falls back to keyword search when the collection has no vector field.
	 * Uses the query text as a standard Solr query against the content field.
	 */
	private List<Document> executeKeywordFallback(SearchRequest request) throws SolrServerException, IOException {
		log.info("No vector field in collection '{}' — falling back to keyword search", collection);

		SolrQuery query = new SolrQuery(options.contentFieldName() + ":" + request.getQuery());
		if (request.getTopK() > 0) {
			query.setRows(request.getTopK());
		}

		applyFiltersAndFields(query, request);

		QueryResponse response = solrClient.query(collection, query, SolrRequest.METHOD.POST);
		List<SolrDocument> results = response.getResults();

		log.debug("Keyword fallback search in collection '{}' returned {} results", collection, results.size());

		return results.stream().map(solrDoc -> toDocument(solrDoc, -1)).filter(Objects::nonNull).toList();
	}

	private void applyFiltersAndFields(SolrQuery query, SearchRequest request) {
		if (request.getFilterExpression() != null) {
			String solrFilter = convertFilterToSolrQuery(request.getFilterExpression());
			if (solrFilter != null && !solrFilter.isEmpty()) {
				query.addFilterQuery(solrFilter);
			}
		}

		query.setFields(options.idFieldName(), options.contentFieldName(), "score",
				options.metadataPrefix() + "*");
	}

	@Override
	public VectorStoreObservationContext.Builder createObservationContextBuilder(String operationType) {
		return VectorStoreObservationContext.builder("solr", operationType).collectionName(this.collection)
				.dimensions(this.options.vectorDimension()).namespace(this.collection);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> Optional<T> getNativeClient() {
		return (Optional<T>) Optional.of(this.solrClient);
	}

	private void initializeSchema() {
		log.debug("Schema initialization not implemented - assuming schema exists in Solr");
	}

	/**
	 * Checks whether the configured vector field exists in the collection's schema.
	 * The result is cached after the first successful check.
	 *
	 * @return {@code true} if the vector field exists, {@code false} otherwise
	 */
	boolean hasVectorField() {
		Boolean cached = vectorFieldAvailable.get();
		if (cached != null) {
			return cached;
		}
		boolean result = detectVectorField();
		vectorFieldAvailable.set(result);
		return result;
	}

	private boolean detectVectorField() {
		try {
			SchemaRequest.Fields fieldsRequest = new SchemaRequest.Fields();
			SchemaResponse.FieldsResponse response = fieldsRequest.process(solrClient, collection);
			List<Map<String, Object>> fields = response.getFields();
			if (fields != null) {
				for (Map<String, Object> field : fields) {
					if (options.vectorFieldName().equals(field.get("name"))) {
						log.debug("Vector field '{}' found in collection '{}'", options.vectorFieldName(),
								collection);
						return true;
					}
				}
			}
			log.info("Vector field '{}' not found in collection '{}' — falling back to keyword-only mode",
					options.vectorFieldName(), collection);
			return false;
		}
		catch (Exception e) {
			log.warn("Failed to check schema for vector field '{}' in collection '{}': {}. "
					+ "Assuming no vector field — falling back to keyword-only mode", options.vectorFieldName(),
					collection, e.getMessage());
			return false;
		}
	}

	String convertFilterToSolrQuery(Filter.Expression filterExpression) {
		if (filterExpression == null) {
			return null;
		}
		return convertOperand(filterExpression);
	}

	private String convertOperand(Filter.Operand operand) {
		if (operand instanceof Filter.Expression expression) {
			return convertExpression(expression);
		}
		else if (operand instanceof Filter.Group group) {
			return "(" + convertOperand(group.content()) + ")";
		}
		else if (operand instanceof Filter.Key key) {
			String fieldName = key.key();
			if (!fieldName.startsWith(options.metadataPrefix())) {
				fieldName = options.metadataPrefix() + fieldName;
			}
			return fieldName;
		}
		else if (operand instanceof Filter.Value value) {
			Object v = value.value();
			if (v instanceof String s) {
				return "\"" + s + "\"";
			}
			return String.valueOf(v);
		}
		return "";
	}

	private String convertExpression(Filter.Expression expression) {
		String left = convertOperand(expression.left());
		String right = convertOperand(expression.right());

		return switch (expression.type()) {
			case EQ -> left + ":" + right;
			case NE -> "-" + left + ":" + right;
			case GT -> left + ":{" + right + " TO *]";
			case GTE -> left + ":[" + right + " TO *]";
			case LT -> left + ":[* TO " + right + "}";
			case LTE -> left + ":[* TO " + right + "]";
			case AND -> left + " AND " + right;
			case OR -> left + " OR " + right;
			case IN -> left + ":(" + right.replace("\"", "") + ")";
			default -> {
				log.warn("Unsupported filter expression type, ignoring: {}", expression.type());
				yield "";
			}
		};
	}

	private SolrInputDocument toSolrDocument(Document document) {
		SolrInputDocument solrDoc = new SolrInputDocument();

		String id = document.getId() != null ? document.getId() : UUID.randomUUID().toString();
		solrDoc.addField(options.idFieldName(), id);
		solrDoc.addField(options.contentFieldName(), document.getText());

		Object embeddingObj = document.getMetadata().get("embedding");
		if (embeddingObj instanceof float[] embedding) {
			List<Float> embeddingList = new ArrayList<>();
			for (float val : embedding) {
				embeddingList.add(val);
			}
			solrDoc.addField(options.vectorFieldName(), embeddingList);
		}

		if (document.getMetadata() != null) {
			document.getMetadata().forEach((key, value) -> {
				if (!options.idFieldName().equals(key) && !options.contentFieldName().equals(key)
						&& !options.vectorFieldName().equals(key) && !"embedding".equals(key)) {
					solrDoc.addField(options.metadataPrefix() + key, value);
				}
			});
		}

		return solrDoc;
	}

	@SuppressWarnings("unchecked")
	private Document toDocument(SolrDocument solrDoc, double similarityThreshold) {
		Object idObj = solrDoc.getFieldValue(options.idFieldName());
		String id = null;
		if (idObj instanceof List) {
			List<?> idList = (List<?>) idObj;
			if (!idList.isEmpty()) {
				id = idList.getFirst().toString();
			}
		}
		else if (idObj != null) {
			id = idObj.toString();
		}

		Object contentObj = solrDoc.getFieldValue(options.contentFieldName());
		String content = null;
		if (contentObj instanceof List) {
			List<?> contentList = (List<?>) contentObj;
			if (!contentList.isEmpty()) {
				content = contentList.getFirst().toString();
			}
		}
		else if (contentObj != null) {
			content = contentObj.toString();
		}

		Number scoreNum = (Number) solrDoc.getFieldValue("score");
		Double score = scoreNum != null ? scoreNum.doubleValue() : null;

		if (score != null && similarityThreshold >= 0) {
			if (score < similarityThreshold) {
				return null;
			}
		}

		Map<String, Object> metadata = new HashMap<>();
		solrDoc.getFieldNames().forEach(fieldName -> {
			if (fieldName.startsWith(options.metadataPrefix())) {
				String metadataKey = fieldName.substring(options.metadataPrefix().length());
				Object metadataValue = solrDoc.getFieldValue(fieldName);

				if (metadataValue instanceof List) {
					List<?> valueList = (List<?>) metadataValue;
					if (!valueList.isEmpty()) {
						Object firstValue = valueList.getFirst();
						if (firstValue instanceof Long) {
							metadata.put(metadataKey, ((Long) firstValue).intValue());
						}
						else {
							metadata.put(metadataKey, firstValue);
						}
					}
				}
				else if (metadataValue != null) {
					if (metadataValue instanceof Long) {
						metadata.put(metadataKey, ((Long) metadataValue).intValue());
					}
					else {
						metadata.put(metadataKey, metadataValue);
					}
				}
			}
		});

		if (score != null) {
			metadata.put("score", score);
		}

		return Document.builder().id(id).text(content).metadata(metadata).build();
	}

	/**
	 * Builder for SolrVectorStore.
	 */
	public static final class Builder extends AbstractVectorStoreBuilder<Builder> {

		private final SolrClient solrClient;

		private final String collection;

		private SolrVectorStoreOptions options;

		private boolean initializeSchema = false;

		private Boolean forceVectorFieldAvailable;

		private Builder(SolrClient solrClient, String collection, EmbeddingModel embeddingModel) {
			super(embeddingModel);
			this.solrClient = solrClient;
			this.collection = collection;
		}

		public Builder options(SolrVectorStoreOptions options) {
			this.options = options;
			return this;
		}

		public Builder initializeSchema(boolean initializeSchema) {
			this.initializeSchema = initializeSchema;
			return this;
		}

		/**
		 * Pre-sets whether the vector field is available, bypassing schema detection.
		 * Useful for testing or when the caller already knows the schema.
		 *
		 * @param available
		 *            {@code true} if vector field exists, {@code false} if not
		 * @return this builder
		 */
		public Builder forceVectorFieldAvailable(boolean available) {
			this.forceVectorFieldAvailable = available;
			return this;
		}

		@Override
		public SolrVectorStore build() {
			return new SolrVectorStore(this);
		}
	}
}
