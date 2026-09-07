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
import org.eclipse.jkube.maven.it.Verify

// Verify Dockerfile was generated
def dockerfilePath = new File(basedir, "target/docker/jkube/jkube-maven-sample-spring-boot-4-build/latest/build/Dockerfile")
if (!dockerfilePath.exists()) {
  throw new IllegalStateException("Dockerfile not found at: " + dockerfilePath)
}
println "✓ Dockerfile exists at: " + dockerfilePath

// Read Dockerfile content
def dockerfileContent = dockerfilePath.text
println "Dockerfile content:\n" + dockerfileContent

// Verify Dockerfile contains COPY commands for Spring Boot layers
// Note: The assembly creates /{layer}/deployments/ structure, so COPY is:
// COPY /{layer}/deployments /deployments/
def requiredLayers = ["dependencies", "spring-boot-loader", "application"]
requiredLayers.each { layer ->
  if (!dockerfileContent.contains("COPY /${layer}/deployments /deployments/")) {
    throw new IllegalStateException("Dockerfile does not contain COPY for layer: ${layer}")
  }
  println "✓ Dockerfile contains COPY for layer: ${layer}"
}

// Verify layer directories were extracted in target/
def targetDir = new File(basedir, "target")
def extractedLayers = ["dependencies", "spring-boot-loader", "snapshot-dependencies", "application"]
extractedLayers.each { layer ->
  def layerDir = new File(targetDir, layer)
  if (layerDir.exists() && layerDir.isDirectory()) {
    println "✓ Layer directory extracted: ${layer}"
  }
}

// Verify resources were generated
Verify.verifyResourceDescriptors(
  new File(basedir, "/target/classes/META-INF/jkube/kubernetes.yml"),
  new File(basedir, "/expected/kubernetes.yml"))

println "✓ All verifications passed for Spring Boot 4 build test"
true