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
package org.apache.solr.mcp.server.util;

/**
 * Utility class for constructing Solr KNN query strings.
 *
 * <p>
 * Builds queries in the format {@code {!knn f=<field> topK=<k>}<vectorString>}
 * for finding the topK most similar documents based on vector similarity.
 */
public final class SolrQueryUtils {

	public static final String DEFAULT_VECTOR_FIELD = "vector";

	private SolrQueryUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * Builds a KNN query string for Solr dense vector search.
	 *
	 * @param vectorFieldName
	 *            the name of the vector field in Solr
	 * @param topK
	 *            the number of nearest neighbors to retrieve
	 * @param vectorString
	 *            the query vector formatted as a Solr-compatible string
	 * @return the KNN query string
	 * @throws IllegalArgumentException
	 *             if parameters are invalid
	 */
	public static String buildKnnQuery(String vectorFieldName, int topK, String vectorString) {
		if (vectorFieldName == null || vectorFieldName.isBlank()) {
			throw new IllegalArgumentException("Vector field name must not be null or blank");
		}
		if (topK <= 0) {
			throw new IllegalArgumentException("topK must be greater than 0");
		}
		if (vectorString == null) {
			throw new IllegalArgumentException("Vector string must not be null");
		}
		return String.format("{!knn f=%s topK=%d}%s", vectorFieldName, topK, vectorString);
	}

	/**
	 * Builds a KNN query string using the default vector field name.
	 *
	 * @param topK
	 *            the number of nearest neighbors to retrieve
	 * @param vectorString
	 *            the query vector formatted as a Solr-compatible string
	 * @return the KNN query string
	 */
	public static String buildKnnQuery(int topK, String vectorString) {
		return buildKnnQuery(DEFAULT_VECTOR_FIELD, topK, vectorString);
	}
}
