/*
 * Copyright (c) 2019 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at:
 *
 *     https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package org.eclipse.jkube.kit.config.image.build.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CACertificateManagerTest {

  @Test
  void generateCertInstallCommands_withNullBaseImage_shouldReturnEmpty() {
    List<String> result = CACertificateManager.generateCertInstallCommands(null, Collections.singletonList("/path/to/cert.crt"));
    assertThat(result).isEmpty();
  }

  @Test
  void generateCertInstallCommands_withEmptyCerts_shouldReturnEmpty() {
    List<String> result = CACertificateManager.generateCertInstallCommands("openjdk:11-jre", Collections.emptyList());
    assertThat(result).isEmpty();
  }

  @Test
  void generateCertInstallCommands_withAlpineJavaImage_shouldIncludeJavaCommands() {
    List<String> certs = Arrays.asList("/path/to/cert1.crt", "/path/to/cert2.crt");
    List<String> result = CACertificateManager.generateCertInstallCommands("openjdk:11-jre-alpine", certs);

    assertThat(result).isNotEmpty();
    assertThat(result).anyMatch(cmd -> cmd.contains("update-ca-certificates"));
    assertThat(result).anyMatch(cmd -> cmd.contains("keytool"));
  }

  @Test
  void generateCertInstallCommands_withDebianJavaImage_shouldIncludeJavaCommands() {
    List<String> certs = Collections.singletonList("/path/to/cert.crt");
    List<String> result = CACertificateManager.generateCertInstallCommands("openjdk:11-jre-slim", certs);

    assertThat(result).isNotEmpty();
    assertThat(result).anyMatch(cmd -> cmd.contains("update-ca-certificates"));
    assertThat(result).anyMatch(cmd -> cmd.contains("keytool"));
  }

  @Test
  void generateCertInstallCommands_withRedHatImage_shouldUseUpdateCaTrust() {
    List<String> certs = Collections.singletonList("/path/to/cert.crt");
    List<String> result = CACertificateManager.generateCertInstallCommands("registry.access.redhat.com/ubi8/openjdk-11", certs);

    assertThat(result).isNotEmpty();
    assertThat(result).anyMatch(cmd -> cmd.contains("update-ca-trust"));
    assertThat(result).anyMatch(cmd -> cmd.contains("keytool"));
  }

  @Test
  void generateCopyCertEntries_withCerts_shouldCreateCorrectEntries() {
    List<String> certs = Arrays.asList("/path/to/cert1.crt", "/path/to/cert2.pem");
    List<CACertificateManager.CopyCertEntry> result = CACertificateManager.generateCopyCertEntries(certs, "/tmp/certs");

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getSource()).isEqualTo("/path/to/cert1.crt");
    assertThat(result.get(0).getDestination()).contains("/tmp/certs/cert-0-cert1.crt");
    assertThat(result.get(1).getSource()).isEqualTo("/path/to/cert2.pem");
    assertThat(result.get(1).getDestination()).contains("/tmp/certs/cert-1-cert2.pem");
  }

  @Test
  void generateCopyCertEntries_withEmptyCerts_shouldReturnEmpty() {
    List<CACertificateManager.CopyCertEntry> result = CACertificateManager.generateCopyCertEntries(Collections.emptyList(), "/tmp/certs");
    assertThat(result).isEmpty();
  }

  @Test
  void generateCopyCertEntries_withNullCerts_shouldReturnEmpty() {
    List<CACertificateManager.CopyCertEntry> result = CACertificateManager.generateCopyCertEntries(null, "/tmp/certs");
    assertThat(result).isEmpty();
  }
}
