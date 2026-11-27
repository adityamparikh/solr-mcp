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

import org.apache.solr.mcp.server.indexing.IndexingService.IndexTool;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pins how the {@code index-data} prompt's {@code format} argument maps to an indexing tool. */
class IndexToolTest {

  @ParameterizedTest
  @CsvSource({
    "json, JSON",
    "csv, CSV",
    "xml, XML",
    "markdown, MARKDOWN",
    "md, MARKDOWN",
    "' JSON ', JSON",
    "Md, MARKDOWN"
  })
  void forFormatResolvesKeywordsAndAliasesIgnoringCaseAndWhitespace(
      String format, IndexTool expected) {
    assertThat(IndexTool.forFormat(format)).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"yaml", "", "  ", "jsonl"})
  void forFormatRejectsUnsupportedFormats(String format) {
    assertThatThrownBy(() -> IndexTool.forFormat(format))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("format must be one of json/csv/xml/markdown");
  }
}
