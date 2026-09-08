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
package org.eclipse.jkube.springboot.generator;

import org.eclipse.jkube.generator.api.GeneratorConfig;
import org.eclipse.jkube.generator.api.GeneratorContext;
import org.eclipse.jkube.kit.common.Assembly;
import org.eclipse.jkube.kit.common.AssemblyConfiguration;
import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.KitLogger;
import org.eclipse.jkube.springboot.SpringBootLayeredJar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * Tests for LayeredJarGenerator subdirectory search logic (lines 90-102).
 * Uses Mockito to bypass actual jar extraction and test the findLayerBaseDirectory
 * subdirectory search logic that would be used with Spring Boot 3.3+ tools jarmode.
 */
@DisplayName("LayeredJarGenerator Subdirectory Search Logic")
class LayeredJarGeneratorSubdirectoryTest {

  @TempDir
  private Path tempDir;

  private File targetDir;
  private GeneratorContext generatorContext;
  private GeneratorConfig generatorConfig;

  @BeforeEach
  void setUp() throws IOException {
    File projectBaseDir = tempDir.toFile();
    targetDir = Files.createDirectory(tempDir.resolve("target")).toFile();

    Properties properties = new Properties();
    JavaProject project = JavaProject.builder()
        .baseDirectory(projectBaseDir)
        .buildDirectory(targetDir)
        .buildPackageDirectory(targetDir)
        .properties(properties)
        .version("1.0.0")
        .artifactId("test-app")
        .build();

    generatorContext = GeneratorContext.builder()
        .logger(new KitLogger.SilentLogger())
        .project(project)
        .build();

    generatorConfig = new GeneratorConfig(properties, "spring-boot", null);
  }

  @Test
  @DisplayName("should search subdirectories when root has no dependencies (Spring Boot 3.3+ tools jarmode)")
  void shouldSearchSubdirectoriesWhenRootHasNoDependencies() throws IOException {
    // Given - Create subdirectory structure simulating Spring Boot 3.3+ tools jarmode
    // tools jarmode extracts to: <jar-basename>/dependencies, <jar-basename>/spring-boot-loader, etc.
    File appSubdir = Files.createDirectory(targetDir.toPath().resolve("test-app-1.0.0")).toFile();
    Files.createDirectory(appSubdir.toPath().resolve("dependencies"));
    Files.createDirectory(appSubdir.toPath().resolve("spring-boot-loader"));
    Files.createDirectory(appSubdir.toPath().resolve("snapshot-dependencies"));
    Files.createDirectory(appSubdir.toPath().resolve("application"));

    File layeredJar = createDummyJar();

    // Mock SpringBootLayeredJar to bypass actual jar execution
    try (MockedConstruction<SpringBootLayeredJar> mocked = mockConstruction(SpringBootLayeredJar.class,
        (mock, context) -> {
          // Mock extractLayers to do nothing (we manually created the structure)
          doNothing().when(mock).extractLayers(any(File.class));

          // Mock listLayers to return the layers we created
          when(mock.listLayers()).thenReturn(Arrays.asList(
              "dependencies", "spring-boot-loader", "snapshot-dependencies", "application"
          ));

          // Mock getMainClass
          when(mock.getMainClass()).thenReturn("org.springframework.boot.loader.launch.JarLauncher");
        })) {

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When - createAssemblyConfiguration is called
      // It will call extractLayers (mocked to no-op), then findLayerBaseDirectory
      // findLayerBaseDirectory should NOT find dependencies in root,
      // so it will loop through subdirectories (LINES 91-98) and find appSubdir
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should successfully create assembly with layers from subdirectory
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1) // jkube-includes + actual layers
          .extracting(Assembly::getId)
          .contains("dependencies", "spring-boot-loader", "snapshot-dependencies", "application");
    }
  }

  @Test
  @DisplayName("should handle multiple subdirectories and find correct one with dependencies")
  void shouldFindCorrectSubdirAmongMany() throws IOException {
    // Given - Multiple subdirectories, only one has dependencies
    Files.createDirectory(targetDir.toPath().resolve("build-temp"));
    Files.createDirectory(targetDir.toPath().resolve("classes"));

    File correctSubdir = Files.createDirectory(targetDir.toPath().resolve("app-1.0.0")).toFile();
    Files.createDirectory(correctSubdir.toPath().resolve("dependencies"));
    Files.createDirectory(correctSubdir.toPath().resolve("spring-boot-loader"));
    Files.createDirectory(correctSubdir.toPath().resolve("application"));

    Files.createDirectory(targetDir.toPath().resolve("other"));

    File layeredJar = createDummyJar();

    try (MockedConstruction<SpringBootLayeredJar> mocked = mockConstruction(SpringBootLayeredJar.class,
        (mock, context) -> {
          doNothing().when(mock).extractLayers(any(File.class));
          when(mock.listLayers()).thenReturn(Arrays.asList("dependencies", "spring-boot-loader", "application"));
          when(mock.getMainClass()).thenReturn("org.springframework.boot.loader.launch.JarLauncher");
        })) {

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When - findLayerBaseDirectory loops through subdirs (LINES 93-98)
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should find layers in the correct subdirectory
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1)
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }
  }

  @Test
  @DisplayName("should return buildPackageDirectory when no dependencies found anywhere (default fallback)")
  void shouldReturnBuildPackageDirWhenNoDependenciesFound() throws IOException {
    // Given - Subdirectories exist but NONE have dependencies folder
    Files.createDirectory(targetDir.toPath().resolve("classes"));
    Files.createDirectory(targetDir.toPath().resolve("generated-sources"));

    File layeredJar = createDummyJar();

    try (MockedConstruction<SpringBootLayeredJar> mocked = mockConstruction(SpringBootLayeredJar.class,
        (mock, context) -> {
          doNothing().when(mock).extractLayers(any(File.class));
          // Return empty list since no layers were found
          when(mock.listLayers()).thenReturn(Collections.emptyList());
          when(mock.getMainClass()).thenReturn("org.springframework.boot.loader.launch.JarLauncher");
        })) {

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When - findLayerBaseDirectory loops but finds nothing, returns default (LINE 102)
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should create config with just jkube-includes (no Spring Boot layers)
      assertThat(config.getLayers())
          .hasSize(1) // Only jkube-includes
          .first()
          .extracting(Assembly::getId)
          .isEqualTo("jkube-includes");
    }
  }

  private File createDummyJar() throws IOException {
    File jarFile = new File(tempDir.toFile(), "dummy.jar");
    Files.createFile(jarFile.toPath());
    return jarFile;
  }
}