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
package org.apache.solr.mcp.server.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayOutputStream;
import java.util.Collection;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.mcp.server.SolrContainer;
import org.apache.solr.mcp.server.TestcontainersConfiguration;
import org.apache.solr.mcp.server.collection.AliasResult;
import org.apache.solr.mcp.server.collection.AliasService;
import org.apache.solr.mcp.server.collection.CollectionService;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.json.JsonContent;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration tests for {@link ClusterService} against the shared single-node SolrCloud container.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClusterServiceIntegrationTest {

  private static final String TEST_COLLECTION = "cluster_test_collection";

  private static final String TEST_ALIAS = "cluster_test_alias";

  /**
   * A copy of the image's {@code _default}, uploaded so that a collection can share it by name.
   * ConfigSet CREATE cannot make it: Solr refuses to copy the trusted {@code _default} for an
   * unauthenticated request, while an upload is accepted and marked untrusted.
   */
  private static final String TEST_CONFIGSET = "cluster_test_conf";

  private static final String SHARED_COLLECTION = "cluster_test_shared";

  /** Created through {@code create-collection} with no configSet, so it gets a private copy. */
  private static final String PRIVATE_COLLECTION = "cluster_test_private";

  /** Where the Solr image keeps {@code _default}, the same path from 9.4 to 10. */
  private static final String IMAGE_DEFAULT_CONF =
      "/opt/solr/server/solr/configsets/_default/conf/";

  private static final String REPLICAS =
      "$.collections." + TEST_COLLECTION + ".shards.shard1.replicas.*";

  @Autowired private ClusterService clusterService;

  @Autowired private CollectionService collectionService;

  @Autowired private SolrClient solrClient;

  @Autowired private SolrContainer solrContainer;

  @Autowired private AliasService aliasService;

  @Autowired private JsonMapper jsonMapper;

  @BeforeAll
  void setupCollections() throws Exception {
    CollectionAdminRequest.createCollection(TEST_COLLECTION, "_default", 1, 1).process(solrClient);
    AliasResult alias = aliasService.createAlias(TEST_ALIAS, TEST_COLLECTION);
    assertThat(alias.success()).isTrue();

    new ConfigSetAdminRequest.Upload()
        .setConfigSetName(TEST_CONFIGSET)
        .setUploadStream(
            new ContentStreamBase.ByteArrayStream(
                zipImageDefaultConfigSet(), TEST_CONFIGSET + ".zip", "application/zip"))
        .process(solrClient);
    CollectionAdminRequest.createCollection(SHARED_COLLECTION, TEST_CONFIGSET, 1, 1)
        .process(solrClient);
    collectionService.createCollection(PRIVATE_COLLECTION, null, null, null);
  }

  /**
   * Every integration test class shares one Solr container. Solr deletes a {@code .AUTOCREATED}
   * copy with its collection, but not a configset created on its own.
   */
  @AfterAll
  void deleteConfigSetCollections() throws Exception {
    CollectionAdminRequest.deleteCollection(PRIVATE_COLLECTION).process(solrClient);
    CollectionAdminRequest.deleteCollection(SHARED_COLLECTION).process(solrClient);
    new ConfigSetAdminRequest.Delete().setConfigSetName(TEST_CONFIGSET).process(solrClient);
  }

  @Test
  void listConfigSetsReportsNamedAndPrivateConfigSetsSorted() throws Exception {
    assertThat(clusterService.listConfigSets().configSets())
        .contains("_default", TEST_CONFIGSET, PRIVATE_COLLECTION + ".AUTOCREATED")
        .isSorted();
  }

  @Test
  void configNameIsTheConfigSetTheCollectionWasCreatedOn() throws Exception {
    assertThat(configNameOf(TEST_COLLECTION)).isEqualTo("_default");
    assertThat(configNameOf(SHARED_COLLECTION)).isEqualTo(TEST_CONFIGSET);
    assertThat(configNameOf(PRIVATE_COLLECTION)).isEqualTo(PRIVATE_COLLECTION + ".AUTOCREATED");
  }

  @Test
  void newCollectionHasOneActiveLeaderOnTheLiveNode() throws Exception {
    JsonContent json =
        new JsonContent(
            jsonMapper.writeValueAsString(clusterService.getClusterStatus(TEST_COLLECTION)));
    String liveNode = JsonPath.read(json.getJson(), "$.live_nodes[0]");

    assertThat(json).extractingPath("$.live_nodes").asArray().hasSize(1);
    assertThat(json).extractingPath("$.collections").asMap().containsOnlyKeys(TEST_COLLECTION);
    assertThat(json)
        .extractingPath("$.collections." + TEST_COLLECTION + ".shards.shard1.state")
        .isEqualTo("active");
    assertThat(json).extractingPath(REPLICAS).asArray().hasSize(1);
    assertThat(json).extractingPath(REPLICAS + ".state").asArray().containsExactly("active");
    // Solr sends "leader": "true", a string; the record writes it as a boolean
    assertThat(json).extractingPath(REPLICAS + ".leader").asArray().containsExactly(true);
    assertThat(json).extractingPath(REPLICAS + ".node_name").asArray().containsExactly(liveNode);
    assertThat(json)
        .extractingPath(REPLICAS + ".core")
        .asArray()
        .allSatisfy(core -> assertThat(core).asString().startsWith(TEST_COLLECTION));
    assertThat(json)
        .extractingPath(REPLICAS + ".base_url")
        .asArray()
        .allSatisfy(baseUrl -> assertThat(baseUrl).asString().endsWith("/solr"));
  }

  @Test
  void recordsTypeWhatTheToolDescriptionTellsTheModelToRead() throws Exception {
    ClusterStatus status = clusterService.getClusterStatus(TEST_COLLECTION);

    ClusterStatus.Collection collection = status.collections().get(TEST_COLLECTION);
    ClusterStatus.Shard shard = collection.shards().get("shard1");
    ClusterStatus.Replica replica = shard.replicas().values().iterator().next();
    assertThat(replica.state()).isEqualTo("active");
    assertThat(replica.leader()).isTrue();
    assertThat(replica.nodeName()).isEqualTo(status.liveNodes().getFirst());
    assertThat(replica.type()).isEqualTo("NRT");
    // properties without a component are kept, not dropped
    assertThat(replica.other()).containsKey("base_url");
    assertThat(collection.other()).containsKey("router");
  }

  @Test
  void aliasReportsTheCollectionBehindIt() throws Exception {
    assertThat(collectionNames(clusterService.getClusterStatus(TEST_ALIAS)))
        .containsExactly(TEST_COLLECTION);
  }

  @Test
  void noCollectionReportsEveryCollection() throws Exception {
    assertThat(collectionNames(clusterService.getClusterStatus(null)))
        .contains(TEST_COLLECTION, SHARED_COLLECTION, PRIVATE_COLLECTION);
  }

  @Test
  void unknownCollectionFailsWithNotFound() {
    assertThatThrownBy(() -> clusterService.getClusterStatus("no_such_collection"))
        .hasMessageContaining("not found");
  }

  @Test
  void resourceReturnsTheSameStatusAsJson() {
    JsonContent json = new JsonContent(clusterService.getClusterStatusResource());

    assertThat(json).doesNotHavePath("$.error");
    assertThat(json).extractingPath("$.live_nodes").asArray().isNotEmpty();
    assertThat(json).extractingPath(REPLICAS).asArray().isNotEmpty();
  }

  /**
   * The image's own {@code _default} conf directory as a zip, so it matches the Solr under test.
   */
  private byte[] zipImageDefaultConfigSet() throws Exception {
    String listing =
        solrContainer.execInContainer("find", IMAGE_DEFAULT_CONF, "-type", "f").getStdout();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (String file : listing.strip().split("\n")) {
        zip.putNextEntry(new ZipEntry(file.substring(IMAGE_DEFAULT_CONF.length())));
        solrContainer.copyFileFromContainer(file, content -> content.transferTo(zip));
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  private @Nullable String configNameOf(String collection) throws Exception {
    return clusterService.getClusterStatus(collection).collections().get(collection).configName();
  }

  private Collection<String> collectionNames(ClusterStatus status) {
    return status.collections().keySet();
  }
}
