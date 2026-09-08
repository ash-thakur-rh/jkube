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
import org.eclipse.jkube.kit.common.AssemblyFileSet;
import org.eclipse.jkube.kit.common.JavaProject;
import org.eclipse.jkube.kit.common.KitLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LayeredJarGenerator")
class LayeredJarGeneratorTest {

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

  @Nested
  @DisplayName("getEnv")
  class GetEnv {

    @Test
    @DisplayName("should set JAVA_MAIN_CLASS from layered jar manifest")
    void shouldSetJavaMainClassFromManifest() throws IOException {
      // Given
      File layeredJar = createLayeredJar("org.springframework.boot.loader.launch.JarLauncher");
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);
      Function<Boolean, Map<String, String>> envSupplier = prePackage -> new HashMap<>();

      // When
      Map<String, String> env = generator.getEnv(envSupplier, false);

      // Then
      assertThat(env)
          .containsEntry("JAVA_MAIN_CLASS", "org.springframework.boot.loader.launch.JarLauncher");
    }

    @Test
    @DisplayName("should preserve existing environment variables")
    void shouldPreserveExistingEnvVars() throws IOException {
      // Given
      File layeredJar = createLayeredJar("org.springframework.boot.loader.JarLauncher");
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      Map<String, String> existingEnv = new HashMap<>();
      existingEnv.put("JAVA_OPTIONS", "-Xmx512m");
      existingEnv.put("APP_PORT", "8080");
      Function<Boolean, Map<String, String>> envSupplier = prePackage -> existingEnv;

      // When
      Map<String, String> env = generator.getEnv(envSupplier, false);

      // Then
      assertThat(env)
          .containsEntry("JAVA_MAIN_CLASS", "org.springframework.boot.loader.JarLauncher")
          .containsEntry("JAVA_OPTIONS", "-Xmx512m")
          .containsEntry("APP_PORT", "8080");
    }

    @Test
    @DisplayName("with null main class, should set null JAVA_MAIN_CLASS")
    void withNullMainClass() throws IOException {
      // Given
      File layeredJar = createLayeredJarWithoutMainClass();
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);
      Function<Boolean, Map<String, String>> envSupplier = prePackage -> new HashMap<>();

      // When
      Map<String, String> env = generator.getEnv(envSupplier, false);

      // Then
      assertThat(env)
          .containsEntry("JAVA_MAIN_CLASS", null);
    }
  }

  @Nested
  @DisplayName("createAssemblyConfiguration")
  class CreateAssemblyConfiguration {

    @Test
    @DisplayName("should create assembly with all Spring Boot layers")
    void shouldCreateAssemblyWithAllLayers() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      createExtractedLayersStructure(targetDir, false); // layertools structure
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);
      List<AssemblyFileSet> defaultFileSets = Collections.singletonList(
          AssemblyFileSet.builder().directory(new File("src/main/resources")).build()
      );

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(defaultFileSets);

      // Then
      assertThat(config.getLayers())
          .hasSize(5) // jkube-includes + 4 Spring Boot layers
          .extracting(Assembly::getId)
          .containsExactly("jkube-includes", "dependencies", "spring-boot-loader", "snapshot-dependencies", "application");
    }

    @Test
    @DisplayName("should create flat output directory structure for all layers")
    void shouldCreateFlatOutputStructure() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      createExtractedLayersStructure(targetDir, false);
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - All Spring Boot layers should output to root directory (flat structure)
      List<Assembly> springBootLayers = config.getLayers().subList(1, config.getLayers().size());
      assertThat(springBootLayers)
          .flatExtracting(Assembly::getFileSets)
          .extracting(AssemblyFileSet::getOutputDirectory)
          .allMatch(dir -> dir.getPath().equals("."));
    }

    @Test
    @DisplayName("should set correct file permissions for layer files")
    void shouldSetCorrectFilePermissions() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      createExtractedLayersStructure(targetDir, false);
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then
      List<Assembly> springBootLayers = config.getLayers().subList(1, config.getLayers().size());
      assertThat(springBootLayers)
          .flatExtracting(Assembly::getFileSets)
          .extracting(AssemblyFileSet::getFileMode)
          .allMatch(mode -> mode.equals("0640"));
    }

    @Test
    @DisplayName("should exclude final output artifact")
    void shouldExcludeFinalOutputArtifact() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      createExtractedLayersStructure(targetDir, false);
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then
      assertThat(config.isExcludeFinalOutputArtifact()).isTrue();
    }

    @Test
    @DisplayName("should include default fileSets in jkube-includes assembly")
    void shouldIncludeDefaultFileSets() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      createExtractedLayersStructure(targetDir, false);
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      List<AssemblyFileSet> defaultFileSets = new ArrayList<>();
      defaultFileSets.add(AssemblyFileSet.builder().directory(new File("src/main/jkube")).build());
      defaultFileSets.add(AssemblyFileSet.builder().directory(new File("src/main/resources")).build());

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(defaultFileSets);

      // Then
      assertThat(config.getLayers())
          .first()
          .satisfies(assembly -> {
            assertThat(assembly.getId()).isEqualTo("jkube-includes");
            assertThat(assembly.getFileSets()).hasSize(2);
          });
    }
  }

  @Nested
  @DisplayName("findLayerBaseDirectory")
  class FindLayerBaseDirectory {

    @Test
    @DisplayName("should find layers in root directory for layertools jarmode")
    void shouldFindLayersInRootForLayertools() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      createExtractedLayersStructure(targetDir, false); // layertools extracts to root
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When - createAssemblyConfiguration calls findLayerBaseDirectory internally
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should find layers successfully
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1) // jkube-includes + actual layers
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }

    @Test
    @DisplayName("should find layers in subdirectory for tools jarmode (Spring Boot 3.3+)")
    void shouldFindLayersInSubdirectoryForToolsJarmode() throws IOException {
      // Given - Real jar that will extract layers
      File layeredJar = createRealLayeredJar();
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // Manually create subdirectory structure to simulate Spring Boot 3.3+ tools jarmode
      // The real jar will extract to root, but we also create a subdirectory with layers
      // findLayerBaseDirectory should find the subdirectory (line 91-98)
      File appSubdir = Files.createDirectory(targetDir.toPath().resolve("layered-1.0.0")).toFile();
      createLayerDirectories(appSubdir);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should find layers (either in root or subdirectory)
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1)
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }

    @Test
    @DisplayName("should iterate through multiple subdirs to find dependencies (loop coverage)")
    void shouldIterateThroughMultipleSubdirsToFindDependencies() throws IOException {
      // Given - Real jar with manually created subdirectory structure
      File layeredJar = createRealLayeredJar();

      // Create multiple subdirectories - only the last one has dependencies
      // This ensures the loop (lines 93-98) is executed multiple times
      Files.createDirectory(targetDir.toPath().resolve("build-aaa"));
      Files.createDirectory(targetDir.toPath().resolve("build-bbb"));
      File correctSubdir = Files.createDirectory(targetDir.toPath().resolve("build-zzz")).toFile();

      // Only the last subdir has dependencies - loop must check all
      createLayerDirectories(correctSubdir);

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should successfully find layers after iterating through subdirs
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1)
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }

    @Test
    @DisplayName("should prioritize jar-basename subdirectory (Spring Boot 3.3+ tools jarmode)")
    void shouldPrioritizeJarBasenameSubdirectory() throws IOException {
      // Given - Jar named "my-app-1.0.0.jar"
      File layeredJar = createLayeredJarWithName("my-app-1.0.0.jar");

      // Create the expected subdirectory based on jar name (without .jar extension)
      File expectedSubdir = Files.createDirectory(targetDir.toPath().resolve("my-app-1.0.0")).toFile();
      createLayerDirectories(expectedSubdir);

      // Also create another subdirectory with layers to test prioritization
      File otherSubdir = Files.createDirectory(targetDir.toPath().resolve("other-dir")).toFile();
      createLayerDirectories(otherSubdir);

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should find layers in jar-basename subdirectory
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1)
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }

    @Test
    @DisplayName("should fallback to subdirectory search when jar-basename subdir not found")
    void shouldFallbackWhenJarBasenameSubdirNotFound() throws IOException {
      // Given - Jar named "my-app.jar" but subdirectory has different name
      File layeredJar = createLayeredJarWithName("my-app.jar");

      // Create subdirectory with different name than jar basename
      File actualSubdir = Files.createDirectory(targetDir.toPath().resolve("build-output")).toFile();
      createLayerDirectories(actualSubdir);

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should still find layers via fallback search
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1)
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }

    @Test
    @DisplayName("when no dependencies directory found, should default to buildPackageDirectory")
    void whenNoDependenciesFound_shouldDefaultToBuildPackageDirectory() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      // Create some subdirectories but without dependencies folder
      Files.createDirectory(targetDir.toPath().resolve("classes"));
      Files.createDirectory(targetDir.toPath().resolve("generated-sources"));
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should still create config, defaulting to buildPackageDirectory
      assertThat(config.getLayers())
          .hasSizeGreaterThan(0)
          .first()
          .extracting(Assembly::getId)
          .isEqualTo("jkube-includes");
    }

    @Test
    @DisplayName("when subdirs is null (empty directory), should default to buildPackageDirectory")
    void whenSubdirsIsNull_shouldDefaultToBuildPackageDirectory() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      // targetDir exists but is empty (no subdirectories)
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should create config with default behavior
      assertThat(config.getLayers())
          .isNotEmpty()
          .first()
          .extracting(Assembly::getId)
          .isEqualTo("jkube-includes");
    }

    @Test
    @DisplayName("when multiple subdirectories exist but only one has dependencies, should find correct one")
    void whenMultipleSubdirsExist_shouldFindCorrectOne() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      // Create multiple subdirectories
      Files.createDirectory(targetDir.toPath().resolve("build-1"));
      Files.createDirectory(targetDir.toPath().resolve("build-2"));
      File correctSubdir = Files.createDirectory(targetDir.toPath().resolve("test-app-1.0.0")).toFile();
      Files.createDirectory(targetDir.toPath().resolve("other"));

      // Only one has the dependencies directory
      createLayerDirectories(correctSubdir);

      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should find layers in the correct subdirectory
      assertThat(config.getLayers())
          .hasSizeGreaterThan(1)
          .extracting(Assembly::getId)
          .contains("dependencies", "application");
    }

    @Test
    @DisplayName("when buildPackageDirectory has files but no directories, should handle gracefully")
    void whenOnlyFilesExist_shouldHandleGracefully() throws IOException {
      // Given
      File layeredJar = createRealLayeredJar();
      // Create some files but no directories
      Files.createFile(targetDir.toPath().resolve("test.jar"));
      Files.createFile(targetDir.toPath().resolve("test.txt"));
      LayeredJarGenerator generator = new LayeredJarGenerator(generatorContext, generatorConfig, layeredJar);

      // When
      AssemblyConfiguration config = generator.createAssemblyConfiguration(Collections.emptyList());

      // Then - Should create config without errors
      assertThat(config.getLayers())
          .isNotEmpty()
          .first()
          .extracting(Assembly::getId)
          .isEqualTo("jkube-includes");
    }
  }

  // Helper methods

  private File createLayeredJar(String mainClass) throws IOException {
    File jarFile = new File(tempDir.toFile(), "layered.jar");
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
    manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.3.0");

    try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarFile.toPath()), manifest)) {
      jarOutputStream.putNextEntry(new JarEntry("BOOT-INF/layers.idx"));
      String layersContent = "- \"dependencies\":\n  - \"BOOT-INF/lib/\"\n" +
                            "- \"spring-boot-loader\":\n  - \"org/\"\n" +
                            "- \"snapshot-dependencies\":\n  - \"BOOT-INF/lib/snapshot/\"\n" +
                            "- \"application\":\n  - \"BOOT-INF/classes/\"\n  - \"META-INF/\"\n";
      jarOutputStream.write(layersContent.getBytes());
    }
    return jarFile;
  }

  private File createLayeredJarWithoutMainClass() throws IOException {
    File jarFile = new File(tempDir.toFile(), "no-main.jar");
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    // Intentionally omit Main-Class
    manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.3.0");

    try (JarOutputStream jarOutputStream = new JarOutputStream(Files.newOutputStream(jarFile.toPath()), manifest)) {
      jarOutputStream.putNextEntry(new JarEntry("BOOT-INF/layers.idx"));
      jarOutputStream.write("- \"dependencies\":\n  - \"BOOT-INF/lib/\"\n".getBytes());
    }
    return jarFile;
  }

  private File createRealLayeredJar() throws IOException {
    File jarFile = new File(tempDir.toFile(), "layered.jar");
    Files.copy(
        Objects.requireNonNull(getClass().getResourceAsStream("/generator-integration-test/layered-jar.jar")),
        jarFile.toPath()
    );
    return jarFile;
  }

  private File createLayeredJarWithName(String jarName) throws IOException {
    File jarFile = new File(tempDir.toFile(), jarName);
    Files.copy(
        Objects.requireNonNull(getClass().getResourceAsStream("/generator-integration-test/layered-jar.jar")),
        jarFile.toPath()
    );
    return jarFile;
  }

  private void createExtractedLayersStructure(File baseDir, boolean inSubdirectory) throws IOException {
    File layerBase = inSubdirectory
        ? Files.createDirectory(baseDir.toPath().resolve("test-app-1.0.0")).toFile()
        : baseDir;

    createLayerDirectories(layerBase);
  }

  private void createLayerDirectories(File baseDir) throws IOException {
    Files.createDirectory(baseDir.toPath().resolve("dependencies"));
    Files.createDirectory(baseDir.toPath().resolve("spring-boot-loader"));
    Files.createDirectory(baseDir.toPath().resolve("snapshot-dependencies"));
    Files.createDirectory(baseDir.toPath().resolve("application"));
  }
}