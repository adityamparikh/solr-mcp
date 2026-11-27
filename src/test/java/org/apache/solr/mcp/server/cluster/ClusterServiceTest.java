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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledInNativeImage;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.json.JsonContent;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisabledInNativeImage
class ClusterServiceTest {

  private static final String SOLR_URL = "http://solr-internal.corp:8983/solr";

  private static final String NODE = "solr1:8983_solr";

  private static final String REPLICA_PATH =
      "$.collections.films.shards.shard1.replicas.core_node2";

  private static final JsonMapper JSON_MAPPER = JsonMapper.shared();

  @Mock private SolrClient solrClient;

  private ClusterService clusterService;

  @BeforeEach
  void setUp() {
    clusterService = new ClusterService(solrClient, JSON_MAPPER);
  }

  @Test
  void getClusterStatus_coercesLeaderAndKeepsUntypedProperties() throws Exception {
    SimpleOrderedMap<Object> cluster = dummyCluster();
    cluster.add("futureSection", "x");
    when(solrClient.request(any(), any())).thenReturn(solrMap("cluster", cluster));

    ClusterStatus status = clusterService.getClusterStatus("films");

    ClusterStatus.Replica parsed =
        status.collections().get("films").shards().get("shard1").replicas().get("core_node2");
    assertEquals(Boolean.TRUE, parsed.leader());
    assertEquals(NODE, parsed.nodeName());
    assertEquals(SOLR_URL, parsed.other().get("base_url"));
    assertEquals(List.of(NODE), status.liveNodes());
    assertEquals("x", status.other().get("futureSection"));
    JsonContent json = new JsonContent(JSON_MAPPER.writeValueAsString(status));
    assertThat(json).extractingPath(REPLICA_PATH + ".leader").isEqualTo(true);
    assertThat(json)
        .extractingPath("$.collections.films.router")
        .isEqualTo(Map.of("name", "compositeId"));
    assertThat(json).extractingPath("$.futureSection").isEqualTo("x");
    // A deep scan never throws PathNotFoundException, so doesNotHavePath would always fail here
    assertThat(json).extractingPath("$..other").asArray().isEmpty();
  }

  @Test
  void getClusterStatusResource_writesSolrsKeys() throws Exception {
    when(solrClient.request(any(), any())).thenReturn(solrMap("cluster", dummyCluster()));

    JsonContent json = new JsonContent(clusterService.getClusterStatusResource());

    assertThat(json).extractingPath("$.live_nodes").asArray().containsExactly(NODE);
    assertThat(json)
        .extractingPath("$.collections.films.configName")
        .isEqualTo("films.AUTOCREATED");
    assertThat(json).extractingPath(REPLICA_PATH + ".node_name").isEqualTo(NODE);
    assertThat(json).extractingPath(REPLICA_PATH + ".base_url").isEqualTo(SOLR_URL);
  }

  @Test
  void listConfigSets_sortsTheNames() throws Exception {
    when(solrClient.request(any(), any()))
        .thenReturn(solrMap("configSets", List.of("zeta", "_default", "films.AUTOCREATED")));

    assertEquals(
        List.of("_default", "films.AUTOCREATED", "zeta"),
        clusterService.listConfigSets().configSets());
  }

  @Test
  void getClusterStatusResource_SolrFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(new SolrServerException("Server refused connection at: " + SOLR_URL));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> clusterService.getClusterStatusResource());

    assertEquals(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
    assertFalse(e.getMessage().contains(SOLR_URL));
  }

  @Test
  void getClusterStatusResource_IoFailure_DoesNotLeakCause() throws Exception {
    when(solrClient.request(any(), any()))
        .thenThrow(new IOException("Connection reset by " + SOLR_URL));

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> clusterService.getClusterStatusResource());

    assertEquals(ClusterService.CLUSTER_STATUS_RESOURCE_ERROR, e.getMessage());
    assertNull(e.getCause());
    assertFalse(e.getMessage().contains(SOLR_URL));
  }

  /**
   * The {@code cluster} section of a CLUSTERSTATUS response for one healthy collection, {@code
   * films}, with one shard whose only replica is an active NRT leader on {@link #NODE}. Values are
   * written as Solr sends them, including {@code "leader": "true"} as a string.
   */
  private static SimpleOrderedMap<Object> dummyCluster() {
    SimpleOrderedMap<Object> replica =
        solrMap(
            "core", "films_shard1_replica_n1",
            "node_name", NODE,
            "base_url", SOLR_URL,
            "state", "active",
            "type", "NRT",
            "leader", "true");
    SimpleOrderedMap<Object> shard =
        solrMap(
            "range", "80000000-7fffffff",
            "state", "active",
            "replicas", solrMap("core_node2", replica),
            "health", "GREEN");
    SimpleOrderedMap<Object> films =
        solrMap(
            "router",
            Map.of("name", "compositeId"),
            "shards",
            solrMap("shard1", shard),
            "health",
            "GREEN",
            "configName",
            "films.AUTOCREATED");
    return solrMap("collections", solrMap("films", films), "live_nodes", List.of(NODE));
  }

  /** An ordered Solr map from alternating keys and values, the shape SolrJ parses JSON into. */
  private static SimpleOrderedMap<Object> solrMap(Object... keysAndValues) {
    SimpleOrderedMap<Object> map = new SimpleOrderedMap<>();
    for (int index = 0; index < keysAndValues.length; index += 2) {
      map.add((String) keysAndValues[index], keysAndValues[index + 1]);
    }
    return map;
  }
}
