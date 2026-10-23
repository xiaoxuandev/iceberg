/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Namespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

public class TestRESTServerExtension {

  private static final Map<String, String> FREE_PORT_CONFIG =
      Map.of(
          RESTCatalogServer.REST_PORT,
          RESTServerExtension.FREE_PORT,
          CatalogProperties.CLIENT_POOL_SIZE,
          "1");

  @RegisterExtension
  private static final RESTServerExtension REST_SERVER_EXTENSION =
      new RESTServerExtension(FREE_PORT_CONFIG);

  @Test
  public void portIsUnavailableBeforeStart() {
    RESTCatalogServer server = new RESTCatalogServer(Map.of(RESTCatalogServer.REST_PORT, "0"));
    assertThatThrownBy(server::port)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("REST catalog server is not started");
  }

  @Test
  public void freePortIsResolvedAfterStart() {
    String configuredPort = REST_SERVER_EXTENSION.config().get(RESTCatalogServer.REST_PORT);
    assertThat(configuredPort).isNotEqualTo(RESTServerExtension.FREE_PORT);

    int boundPort = Integer.parseInt(configuredPort);
    assertThat(boundPort).isGreaterThan(0);
    assertThat(REST_SERVER_EXTENSION.client().properties().get(CatalogProperties.URI))
        .isEqualTo(String.format("http://localhost:%d/", boundPort));
  }

  @Test
  public void clientTalksToTheStartedServer() {
    Namespace namespace = Namespace.of("ns_" + System.nanoTime());
    REST_SERVER_EXTENSION.client().createNamespace(namespace);
    try {
      assertThat(REST_SERVER_EXTENSION.client().listNamespaces()).contains(namespace);
    } finally {
      REST_SERVER_EXTENSION.client().dropNamespace(namespace);
    }
  }

  @Test
  public void serversStartedOnFreePortsDoNotShareState() throws Exception {
    RESTCatalogServer other = new RESTCatalogServer(Map.of(RESTCatalogServer.REST_PORT, "0"));
    other.start(false);
    try (RESTCatalog otherClient =
        RCKUtils.initCatalogClient(
            Map.of(RESTCatalogServer.REST_PORT, String.valueOf(other.port())))) {
      int extensionPort =
          Integer.parseInt(REST_SERVER_EXTENSION.config().get(RESTCatalogServer.REST_PORT));
      assertThat(other.port()).isGreaterThan(0).isNotEqualTo(extensionPort);

      Namespace namespace = Namespace.of("only_on_extension_server");
      REST_SERVER_EXTENSION.client().createNamespace(namespace);
      try {
        assertThat(otherClient.listNamespaces()).doesNotContain(namespace);
      } finally {
        REST_SERVER_EXTENSION.client().dropNamespace(namespace);
      }
    } finally {
      other.stop();
    }
  }
}
