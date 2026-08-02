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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.mcp.server.indexing.documentcreator.DocumentProcessingException;
import org.apache.solr.mcp.server.indexing.documentcreator.IndexingDocumentCreator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Structural validation tests for JSON indexing.
 *
 * <p>
 * These cases all guard against the same failure mode: input that Solr cannot
 * meaningfully index being accepted and turned into empty documents, so that
 * indexing reports success while storing nothing. Every rejection here used to
 * be a silent no-op.
 *
 * @see org.apache.solr.mcp.server.indexing.documentcreator.JsonDocumentCreator
 */
@SpringBootTest
@TestPropertySource(locations = "classpath:application.properties")
class JsonIndexingTest {

	@Autowired
	private IndexingDocumentCreator indexingDocumentCreator;

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\n\t "})
	void createFromJson_WithBlankInput_ShouldReject(String blank) {
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromJson(blank))
				.isInstanceOf(DocumentProcessingException.class).hasMessageContaining("JSON input cannot be empty");
	}

	@ParameterizedTest
	@ValueSource(strings = {"42", "\"a string\"", "true", "null"})
	void createFromJson_WithScalarRoot_ShouldReject(String scalarJson) {
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromJson(scalarJson))
				.isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("must be an object or an array of objects");
	}

	@ParameterizedTest
	@ValueSource(strings = {"[1,2,3]", "[\"a\",\"b\"]", "[true]", "[{\"id\":1},2]", "[[1,2]]"})
	void createFromJson_WithNonObjectArrayElement_ShouldReject(String json) {
		// A scalar has no fields, so flattening it yields an *empty* document.
		// Accepting these silently indexed one empty document per element and
		// reported success - the same silent data loss a scalar root now rejects.
		assertThatThrownBy(() -> indexingDocumentCreator.createSchemalessDocumentsFromJson(json))
				.isInstanceOf(DocumentProcessingException.class)
				.hasMessageContaining("JSON array must contain only objects");
	}

	@Test
	void createFromJson_WithEmptyArray_ShouldProduceNoDocuments() throws Exception {
		// An empty array is well-formed and unambiguous: nothing to index.
		assertThat(indexingDocumentCreator.createSchemalessDocumentsFromJson("[]")).isEmpty();
	}

	@Test
	void createFromJson_WithSingleObject_ShouldProduceOneDocument() throws Exception {
		List<SolrInputDocument> documents = indexingDocumentCreator
				.createSchemalessDocumentsFromJson("{\"id\":\"doc-1\",\"name\":\"solitary\"}");

		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("id")).isEqualTo("doc-1");
		assertThat(documents.getFirst().getFieldValue("name")).isEqualTo("solitary");
	}

	@Test
	void createFromJson_WithNestedArrayInsideField_ShouldKeepScalarsAndDropNestedArrays() throws Exception {
		// Jackson's asString() throws on a container node, so nested arrays are
		// skipped exactly as object elements already were.
		List<SolrInputDocument> documents = indexingDocumentCreator
				.createSchemalessDocumentsFromJson("[{\"id\":\"doc-1\",\"tags\":[[\"nested\"],\"kept\",{\"o\":1}]}]");

		assertThat(documents).hasSize(1);
		assertThat(documents.getFirst().getFieldValue("id")).isEqualTo("doc-1");
		assertThat(documents.getFirst().getFieldValues("tags")).containsExactly("kept");
	}
}
