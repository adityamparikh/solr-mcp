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

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.FacetParams;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Spring Service providing comprehensive search capabilities for Apache Solr
 * collections through Model Context Protocol (MCP) integration.
 *
 * <p>
 * This service serves as the primary interface for executing search operations
 * against Solr collections, offering a rich set of features including text
 * search, filtering, faceting, sorting, and pagination. It transforms complex
 * Solr query syntax into accessible MCP tools that AI clients can invoke
 * through natural language requests.
 *
 * <p>
 * <strong>Core Features:</strong>
 *
 * <ul>
 * <li><strong>Full-Text Search</strong>: Advanced text search with relevance
 * scoring
 * <li><strong>Semantic Search</strong>: Vector similarity search using
 * embeddings and Solr's KNN query
 * <li><strong>Hybrid Search</strong>: Combines keyword and semantic results
 * using Reciprocal Rank Fusion
 * <li><strong>Filtering</strong>: Multi-criteria filtering using Solr filter
 * queries
 * <li><strong>Faceting</strong>: Dynamic facet generation for result
 * categorization
 * <li><strong>Sorting</strong>: Flexible result ordering by multiple fields
 * <li><strong>Pagination</strong>: Efficient handling of large result sets
 * </ul>
 *
 * <p>
 * <strong>Dynamic Field Support:</strong>
 *
 * <p>
 * The service handles Solr's dynamic field naming conventions where field names
 * include type suffixes that indicate data types and indexing behavior:
 *
 * <ul>
 * <li><strong>_s</strong>: String fields for exact matching
 * <li><strong>_t</strong>: Text fields with tokenization and analysis
 * <li><strong>_i, _l, _f, _d</strong>: Numeric fields (int, long, float,
 * double)
 * <li><strong>_dt</strong>: Date/time fields
 * <li><strong>_b</strong>: Boolean fields
 * </ul>
 *
 * <p>
 * <strong>MCP Tool Integration:</strong>
 *
 * <p>
 * Search operations are exposed as MCP tools that AI clients can invoke through
 * natural language requests such as "search for books by George R.R. Martin" or
 * "find products under $50 in the electronics category".
 *
 * <p>
 * <strong>Response Format:</strong>
 *
 * <p>
 * Returns structured {@link SearchResponse} objects that encapsulate search
 * results, metadata, and facet information in a format optimized for JSON
 * serialization and consumption by AI clients.
 *
 * @version 1.0.0
 * @since 1.0.0
 * @see SearchResponse
 * @see SolrClient
 * @see McpTool
 */
@Service
@Observed
public class SearchService {

	public static final String SORT_ITEM = "item";
	public static final String SORT_ORDER = "order";

	/** Default vector field name used for KNN queries */
	public static final String DEFAULT_VECTOR_FIELD = "vector";

	/** Default topK for KNN queries */
	public static final int DEFAULT_TOP_K = 10;

	/** RRF constant k (smooths rank differences) */
	static final int RRF_K = 60;

	private final SolrClient solrClient;
	private final EmbeddingService embeddingService;

	/**
	 * Constructs a new SearchService with the required dependencies.
	 *
	 * @param solrClient
	 *            the SolrJ client instance for communicating with Solr
	 * @param embeddingService
	 *            the embedding service for generating vector embeddings
	 * @see SolrClient
	 */
	public SearchService(SolrClient solrClient, EmbeddingService embeddingService) {
		this.solrClient = solrClient;
		this.embeddingService = embeddingService;
	}

	/**
	 * Converts a SolrDocumentList to a List of Maps for optimized JSON
	 * serialization.
	 */
	private static List<Map<String, Object>> getDocs(SolrDocumentList documents) {
		List<Map<String, Object>> docs = new ArrayList<>(documents.size());
		documents.forEach(doc -> {
			Map<String, Object> docMap = new HashMap<>();
			for (String fieldName : doc.getFieldNames()) {
				docMap.put(fieldName, doc.getFieldValue(fieldName));
			}
			docs.add(docMap);
		});
		return docs;
	}

	/**
	 * Extracts facet information from a QueryResponse.
	 */
	private static Map<String, Map<String, Long>> getFacets(QueryResponse queryResponse) {
		Map<String, Map<String, Long>> facets = new HashMap<>();
		if (queryResponse.getFacetFields() != null && !queryResponse.getFacetFields().isEmpty()) {
			queryResponse.getFacetFields().forEach(facetField -> {
				Map<String, Long> facetValues = new HashMap<>();
				for (FacetField.Count count : facetField.getValues()) {
					facetValues.put(count.getName(), count.getCount());
				}
				facets.put(facetField.getName(), facetValues);
			});
		}
		return facets;
	}

	/**
	 * Converts a float array embedding to the string format expected by Solr's
	 * KNN query parser: "[f1, f2, f3, ...]".
	 */
	static String vectorToString(float[] embedding) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < embedding.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(embedding[i]);
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * Searches a Solr collection with the specified parameters. This method is
	 * exposed as a tool for MCP clients to use.
	 *
	 * <p>
	 * Supports three search modes:
	 * <ul>
	 * <li><strong>keyword</strong>: Traditional full-text search
	 * <li><strong>semantic</strong>: Vector similarity search using KNN
	 * <li><strong>hybrid</strong>: Combines keyword and semantic results using
	 * Reciprocal Rank Fusion
	 * </ul>
	 *
	 * <p>
	 * When mode is not specified, the smart default applies: hybrid if an
	 * embedding model is configured, keyword if not.
	 *
	 * @param collection
	 *            The Solr collection to query
	 * @param query
	 *            The Solr query string (q parameter). Defaults to "*:*" if not
	 *            specified
	 * @param filterQueries
	 *            List of filter queries (fq parameter)
	 * @param facetFields
	 *            List of fields to facet on
	 * @param sortClauses
	 *            List of sort clauses for ordering results
	 * @param start
	 *            Starting offset for pagination
	 * @param rows
	 *            Number of rows to return
	 * @param mode
	 *            Search mode: keyword, semantic, or hybrid. Defaults to hybrid
	 *            if embedding model is configured, keyword otherwise
	 * @param topK
	 *            Number of nearest neighbors for KNN query (semantic/hybrid
	 *            modes). Defaults to 10
	 * @param vectorField
	 *            Name of the vector field in the Solr schema. Defaults to
	 *            "vector"
	 * @return A SearchResponse containing the search results and facets
	 * @throws SolrServerException
	 *             If there's an error communicating with Solr
	 * @throws IOException
	 *             If there's an I/O error
	 */
	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "search", description = """
			Search specified Solr collection with query, optional filters, facets, sorting, and pagination.
			Supports three search modes: keyword (traditional full-text), semantic (vector similarity via KNN),
			and hybrid (combines both using Reciprocal Rank Fusion). When mode is not specified, automatically
			uses hybrid if an embedding model is configured, keyword otherwise.
			Note that solr has dynamic fields where name of field in schema may end with suffixes
			_s: Represents a string field, used for exact string matching.
			_i: Represents an integer field.
			_l: Represents a long field.
			_f: Represents a float field.
			_d: Represents a double field.
			_dt: Represents a date field.
			_b: Represents a boolean field.
			_t: Often used for text fields that undergo tokenization and analysis.
			One example from the books collection:
			{
			      "id":"0553579908",
			      "cat":["book"],
			      "name":["A Clash of Kings"],
			      "price":[7.99],
			      "inStock":[true],
			      "author":["George R.R. Martin"],
			      "series_t":"A Song of Ice and Fire",
			      "sequence_i":2,
			      "genre_s":"fantasy",
			      "_version_":1836275819373133824,
			      "_root_":"0553579908"
			    }
			""")
	public SearchResponse search(@McpToolParam(description = "Solr collection to query") String collection,
			@McpToolParam(description = "Solr q parameter. If none specified defaults to \"*:*\"", required = false) String query,
			@McpToolParam(description = "Solr fq parameter", required = false) List<String> filterQueries,
			@McpToolParam(description = "Solr facet fields", required = false) List<String> facetFields,
			@McpToolParam(description = "Solr sort parameter", required = false) List<Map<String, String>> sortClauses,
			@McpToolParam(description = "Starting offset for pagination", required = false) Integer start,
			@McpToolParam(description = "Number of rows to return", required = false) Integer rows,
			@McpToolParam(description = "Search mode: keyword, semantic, or hybrid. Defaults to hybrid if embedding model is configured, keyword otherwise.", required = false) String mode,
			@McpToolParam(description = "Number of nearest neighbors for KNN query (semantic/hybrid modes). Defaults to 10.", required = false) Integer topK,
			@McpToolParam(description = "Name of the vector field in the Solr schema. Defaults to \"vector\".", required = false) String vectorField)
			throws SolrServerException, IOException {

		// Resolve search mode
		SearchMode searchMode = resolveSearchMode(mode);

		// Validate that embedding is available for semantic/hybrid modes
		if ((searchMode == SearchMode.SEMANTIC || searchMode == SearchMode.HYBRID) && !embeddingService.isConfigured()) {
			throw new IllegalStateException(
					"Semantic/hybrid search requires an embedding model to be configured.");
		}

		return switch (searchMode) {
			case KEYWORD -> executeKeywordSearch(collection, query, filterQueries, facetFields, sortClauses, start,
					rows);
			case SEMANTIC -> executeSemanticSearch(collection, query, filterQueries, facetFields,
					topK != null ? topK : DEFAULT_TOP_K, vectorField != null ? vectorField : DEFAULT_VECTOR_FIELD);
			case HYBRID -> executeHybridSearch(collection, query, filterQueries, facetFields, sortClauses, start, rows,
					topK != null ? topK : DEFAULT_TOP_K, vectorField != null ? vectorField : DEFAULT_VECTOR_FIELD);
		};
	}

	/**
	 * Resolves the search mode from the string parameter, applying smart
	 * defaults.
	 */
	SearchMode resolveSearchMode(String mode) {
		if (mode == null || mode.isBlank()) {
			// Smart default: hybrid if embedding model is available, keyword otherwise
			return embeddingService.isConfigured() ? SearchMode.HYBRID : SearchMode.KEYWORD;
		}
		try {
			return SearchMode.valueOf(mode.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(
					"Invalid mode '" + mode + "'. Accepted values: keyword, semantic, hybrid.");
		}
	}

	/**
	 * Executes a traditional keyword search.
	 */
	private SearchResponse executeKeywordSearch(String collection, String query, List<String> filterQueries,
			List<String> facetFields, List<Map<String, String>> sortClauses, Integer start, Integer rows)
			throws SolrServerException, IOException {

		final SolrQuery solrQuery = new SolrQuery("*:*");
		if (StringUtils.hasText(query)) {
			solrQuery.setQuery(query);
		}

		applyFilterQueries(solrQuery, filterQueries);
		applyFacets(solrQuery, facetFields);
		applySorting(solrQuery, sortClauses);
		applyPagination(solrQuery, start, rows);

		final QueryResponse queryResponse = solrClient.query(collection, solrQuery);
		final SolrDocumentList documents = queryResponse.getResults();

		return new SearchResponse(documents.getNumFound(), documents.getStart(), documents.getMaxScore(),
				getDocs(documents), getFacets(queryResponse));
	}

	/**
	 * Executes a semantic (KNN) search using vector embeddings.
	 */
	private SearchResponse executeSemanticSearch(String collection, String query, List<String> filterQueries,
			List<String> facetFields, int topK, String vectorFieldName)
			throws SolrServerException, IOException {

		if (!StringUtils.hasText(query)) {
			throw new IllegalArgumentException("Semantic search requires a non-empty query string to generate embeddings.");
		}

		float[] embedding = embeddingService.embed(query);

		SolrQuery knnQuery = new SolrQuery();
		knnQuery.setQuery("{!knn f=" + vectorFieldName + " topK=" + topK + "}" + vectorToString(embedding));

		applyFilterQueries(knnQuery, filterQueries);
		applyFacets(knnQuery, facetFields);

		QueryResponse queryResponse;
		try {
			// Use POST for large vectors to avoid URL length limits
			QueryRequest req = new QueryRequest(knnQuery, SolrRequest.METHOD.POST);
			queryResponse = req.process(solrClient, collection);
		} catch (SolrException e) {
			if (isKnnNotSupportedError(e)) {
				throw new IllegalStateException(
						"Semantic and hybrid search require Solr 9.0 or later. Your Solr version does not support vector search.");
			}
			throw e;
		}

		final SolrDocumentList documents = queryResponse.getResults();
		// For KNN, numFound is the actual results returned (≤ topK)
		long numFound = documents.size();

		return new SearchResponse(numFound, 0, documents.getMaxScore(), getDocs(documents),
				getFacets(queryResponse));
	}

	/**
	 * Executes a hybrid search combining keyword and semantic results using
	 * Reciprocal Rank Fusion (RRF).
	 */
	private SearchResponse executeHybridSearch(String collection, String query, List<String> filterQueries,
			List<String> facetFields, List<Map<String, String>> sortClauses, Integer start, Integer rows, int topK,
			String vectorFieldName) throws SolrServerException, IOException {

		if (!StringUtils.hasText(query)) {
			// Fall back to keyword-only for empty queries
			return executeKeywordSearch(collection, query, filterQueries, facetFields, sortClauses, start, rows);
		}

		// Execute keyword search
		int hybridRows = rows != null ? rows : DEFAULT_TOP_K;
		SearchResponse keywordResults = executeKeywordSearch(collection, query, filterQueries, facetFields,
				sortClauses, null, hybridRows);

		// Execute semantic search
		SearchResponse semanticResults;
		try {
			semanticResults = executeSemanticSearch(collection, query, filterQueries, facetFields, topK,
					vectorFieldName);
		} catch (IllegalStateException e) {
			if (e.getMessage() != null && e.getMessage().contains("Solr 9.0")) {
				throw e;
			}
			// If semantic fails for other reasons, fall back to keyword
			return keywordResults;
		}

		// Merge results using RRF
		return mergeWithRRF(keywordResults, semanticResults, start, hybridRows);
	}

	/**
	 * Merges two search result sets using Reciprocal Rank Fusion (RRF).
	 *
	 * <p>
	 * RRF score for each document: {@code RRF_score(d) = Σ 1 / (k + rank(d, list_i))}
	 * where k=60 (standard smoothing constant).
	 */
	SearchResponse mergeWithRRF(SearchResponse keywordResults, SearchResponse semanticResults, Integer start,
			int rows) {
		Map<String, Double> rrfScores = new LinkedHashMap<>();
		Map<String, Map<String, Object>> docMap = new LinkedHashMap<>();

		// Score keyword results
		List<Map<String, Object>> keywordDocs = keywordResults.documents();
		for (int i = 0; i < keywordDocs.size(); i++) {
			String docId = getDocId(keywordDocs.get(i));
			rrfScores.merge(docId, 1.0 / (RRF_K + i + 1), Double::sum);
			docMap.putIfAbsent(docId, keywordDocs.get(i));
		}

		// Score semantic results
		List<Map<String, Object>> semanticDocs = semanticResults.documents();
		for (int i = 0; i < semanticDocs.size(); i++) {
			String docId = getDocId(semanticDocs.get(i));
			rrfScores.merge(docId, 1.0 / (RRF_K + i + 1), Double::sum);
			docMap.putIfAbsent(docId, semanticDocs.get(i));
		}

		// Sort by RRF score descending
		List<String> sortedIds = rrfScores.entrySet().stream()
				.sorted(Map.Entry.<String, Double>comparingByValue().reversed()).map(Map.Entry::getKey)
				.collect(Collectors.toList());

		// Apply pagination
		int startOffset = start != null ? start : 0;
		int endOffset = Math.min(startOffset + rows, sortedIds.size());
		List<String> pageIds = startOffset < sortedIds.size() ? sortedIds.subList(startOffset, endOffset) : List.of();

		List<Map<String, Object>> mergedDocs = pageIds.stream().map(docMap::get).collect(Collectors.toList());

		// Use max of the two facet maps (prefer keyword facets as they reflect full corpus)
		Map<String, Map<String, Long>> facets = keywordResults.facets().isEmpty() ? semanticResults.facets()
				: keywordResults.facets();

		return new SearchResponse(sortedIds.size(), startOffset, null, mergedDocs, facets);
	}

	/**
	 * Extracts a document ID from a document map, preferring the "id" field.
	 */
	private String getDocId(Map<String, Object> doc) {
		Object id = doc.get("id");
		if (id != null) {
			return id.toString();
		}
		// Fall back to hashCode-based ID if no "id" field
		return String.valueOf(doc.hashCode());
	}

	/**
	 * Checks whether a SolrException indicates that KNN is not supported.
	 */
	private boolean isKnnNotSupportedError(SolrException e) {
		String msg = e.getMessage();
		return msg != null && (msg.contains("knn") || msg.contains("DenseVectorField")
				|| msg.contains("Unknown query type"));
	}

	private void applyFilterQueries(SolrQuery solrQuery, List<String> filterQueries) {
		if (!CollectionUtils.isEmpty(filterQueries)) {
			solrQuery.setFilterQueries(filterQueries.toArray(new String[0]));
		}
	}

	private void applyFacets(SolrQuery solrQuery, List<String> facetFields) {
		if (!CollectionUtils.isEmpty(facetFields)) {
			solrQuery.setFacet(true);
			solrQuery.addFacetField(facetFields.toArray(new String[0]));
			solrQuery.setFacetMinCount(1);
			solrQuery.setFacetSort(FacetParams.FACET_SORT_COUNT);
		}
	}

	private void applySorting(SolrQuery solrQuery, List<Map<String, String>> sortClauses) {
		if (!CollectionUtils.isEmpty(sortClauses)) {
			solrQuery.setSorts(sortClauses.stream()
					.map(sortClause -> new SolrQuery.SortClause(sortClause.get(SORT_ITEM), sortClause.get(SORT_ORDER)))
					.toList());
		}
	}

	private void applyPagination(SolrQuery solrQuery, Integer start, Integer rows) {
		if (start != null) {
			solrQuery.setStart(start);
		}
		if (rows != null) {
			solrQuery.setRows(rows);
		}
	}

}
