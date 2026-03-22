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
package org.apache.solr.mcp.server.indexing;

import io.micrometer.observation.annotation.Observed;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.apache.solr.mcp.server.search.EmbeddingService;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.xml.sax.SAXException;

/**
 * Spring Service providing comprehensive document indexing capabilities for
 * Apache Solr collections through Model Context Protocol (MCP) integration.
 *
 * <p>
 * This service handles the conversion of JSON, CSV, and XML documents into
 * Solr-compatible format and manages the indexing process with robust error
 * handling and batch processing capabilities. It employs a schema-less approach
 * where Solr automatically detects field types, eliminating the need for
 * predefined schema configuration.
 *
 * <p>
 * <strong>Core Features:</strong>
 *
 * <ul>
 * <li><strong>Schema-less Indexing</strong>: Automatic field type detection by
 * Solr
 * <li><strong>JSON Processing</strong>: Support for complex nested JSON
 * documents
 * <li><strong>CSV Processing</strong>: Support for comma-separated value files
 * with headers
 * <li><strong>XML Processing</strong>: Support for XML documents with element
 * flattening and attribute handling
 * <li><strong>Batch Processing</strong>: Efficient bulk indexing with
 * configurable batch sizes
 * <li><strong>Error Resilience</strong>: Individual document fallback when
 * batch operations fail
 * <li><strong>Field Sanitization</strong>: Automatic cleanup of field names for
 * Solr compatibility
 * <li><strong>Embedding Generation</strong>: Optional vector embedding
 * generation for semantic search
 * </ul>
 *
 * @version 1.0.0
 * @since 1.0.0
 * @see SolrInputDocument
 * @see SolrClient
 */
@Service
@Observed
public class IndexingService {

	/** Default field name for storing generated embeddings */
	public static final String DEFAULT_VECTOR_FIELD = "vector";

	private static final int DEFAULT_BATCH_SIZE = 1000;

	private final SolrClient solrClient;
	private final IndexingDocumentCreator indexingDocumentCreator;
	private final EmbeddingService embeddingService;

	public IndexingService(SolrClient solrClient, IndexingDocumentCreator indexingDocumentCreator,
			EmbeddingService embeddingService) {
		this.solrClient = solrClient;
		this.indexingDocumentCreator = indexingDocumentCreator;
		this.embeddingService = embeddingService;
	}

	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "index-json-documents", description = "Index documents from json String into Solr collection. Optionally generates vector embeddings from specified text fields for semantic search.")
	public void indexJsonDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "JSON string containing documents to index") String json,
			@McpToolParam(description = "When true, generates vector embeddings from textFields and stores them in each document", required = false) Boolean generateEmbeddings,
			@McpToolParam(description = "List of field names whose values are concatenated for embedding generation", required = false) List<String> textFields)
			throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromJson(json);
		addEmbeddingsIfRequested(schemalessDoc, generateEmbeddings, textFields);
		indexDocuments(collection, schemalessDoc);
	}

	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "index-csv-documents", description = "Index documents from CSV string into Solr collection. Optionally generates vector embeddings from specified text fields for semantic search.")
	public void indexCsvDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "CSV string containing documents to index") String csv,
			@McpToolParam(description = "When true, generates vector embeddings from textFields and stores them in each document", required = false) Boolean generateEmbeddings,
			@McpToolParam(description = "List of field names whose values are concatenated for embedding generation", required = false) List<String> textFields)
			throws IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromCsv(csv);
		addEmbeddingsIfRequested(schemalessDoc, generateEmbeddings, textFields);
		indexDocuments(collection, schemalessDoc);
	}

	@PreAuthorize("isAuthenticated()")
	@McpTool(name = "index-xml-documents", description = "Index documents from XML string into Solr collection. Optionally generates vector embeddings from specified text fields for semantic search.")
	public void indexXmlDocuments(@McpToolParam(description = "Solr collection to index into") String collection,
			@McpToolParam(description = "XML string containing documents to index") String xml,
			@McpToolParam(description = "When true, generates vector embeddings from textFields and stores them in each document", required = false) Boolean generateEmbeddings,
			@McpToolParam(description = "List of field names whose values are concatenated for embedding generation", required = false) List<String> textFields)
			throws ParserConfigurationException, SAXException, IOException, SolrServerException {
		List<SolrInputDocument> schemalessDoc = indexingDocumentCreator.createSchemalessDocumentsFromXml(xml);
		addEmbeddingsIfRequested(schemalessDoc, generateEmbeddings, textFields);
		indexDocuments(collection, schemalessDoc);
	}

	/**
	 * Generates and adds vector embeddings to documents when requested.
	 *
	 * <p>
	 * For each document, concatenates the values of the specified text fields
	 * (separated by spaces), generates an embedding, and stores it in the
	 * document's "vector" field. Documents where all specified text fields are
	 * missing are skipped (no vector field added).
	 */
	void addEmbeddingsIfRequested(List<SolrInputDocument> documents, Boolean generateEmbeddings,
			List<String> textFields) {
		if (generateEmbeddings == null || !generateEmbeddings) {
			return;
		}

		if (!embeddingService.isConfigured()) {
			throw new IllegalStateException("Embedding generation requires an embedding model to be configured.");
		}

		if (CollectionUtils.isEmpty(textFields)) {
			throw new IllegalArgumentException(
					"textFields must be specified when generateEmbeddings is true.");
		}

		for (SolrInputDocument doc : documents) {
			String concatenatedText = extractTextForEmbedding(doc, textFields);
			if (concatenatedText.isBlank()) {
				// Skip documents where all text fields are missing
				continue;
			}

			float[] embedding = embeddingService.embed(concatenatedText);
			doc.addField(DEFAULT_VECTOR_FIELD, embedding);
		}
	}

	/**
	 * Extracts and concatenates text from the specified fields in a document.
	 */
	private String extractTextForEmbedding(SolrInputDocument doc, List<String> textFields) {
		return textFields.stream().map(field -> {
			Object value = doc.getFieldValue(field);
			return value != null ? value.toString() : "";
		}).filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
	}

	/**
	 * Indexes a list of SolrInputDocument objects into a Solr collection using
	 * batch processing.
	 */
	public int indexDocuments(String collection, List<SolrInputDocument> documents)
			throws SolrServerException, IOException {
		int successCount = 0;
		final int batchSize = DEFAULT_BATCH_SIZE;

		for (int i = 0; i < documents.size(); i += batchSize) {
			final int endIndex = Math.min(i + batchSize, documents.size());
			final List<SolrInputDocument> batch = documents.subList(i, endIndex);

			try {
				solrClient.add(collection, batch);
				successCount += batch.size();
			} catch (SolrServerException | IOException | RuntimeException e) {
				for (SolrInputDocument doc : batch) {
					try {
						solrClient.add(collection, doc);
						successCount++;
					} catch (SolrServerException | IOException | RuntimeException _) {
						// Document failed to index - continue processing the rest
					}
				}
			}
		}

		solrClient.commit(collection);
		return successCount;
	}

}
