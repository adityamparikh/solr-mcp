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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for vector formatting operations.
 *
 * <p>
 * Provides static methods for converting vector representations between
 * different formats, particularly for Apache Solr's KNN query parser
 * requirements.
 */
public final class VectorFormatUtils {

	private VectorFormatUtils() {
		throw new UnsupportedOperationException("Utility class cannot be instantiated");
	}

	/**
	 * Converts a primitive float array to a List of Float objects.
	 *
	 * @param embedding
	 *            the primitive float array to convert
	 * @return a List containing the same values as Float objects
	 */
	public static List<Float> convertToList(float[] embedding) {
		List<Float> result = new ArrayList<>(embedding.length);
		for (float f : embedding) {
			result.add(f);
		}
		return result;
	}

	/**
	 * Converts a List of Float values to a Solr-compatible vector string format.
	 *
	 * @param embedding
	 *            the embedding vector as a List of Floats
	 * @return a string representation suitable for Solr KNN queries
	 */
	public static String formatVectorForSolr(List<Float> embedding) {
		return embedding.stream().map(String::valueOf).collect(Collectors.joining(", ", "[", "]"));
	}

	/**
	 * Converts a primitive float array to a Solr-compatible vector string format.
	 *
	 * @param embedding
	 *            the embedding vector as a primitive float array
	 * @return a string representation suitable for Solr KNN queries
	 */
	public static String formatVectorForSolr(float[] embedding) {
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
}
