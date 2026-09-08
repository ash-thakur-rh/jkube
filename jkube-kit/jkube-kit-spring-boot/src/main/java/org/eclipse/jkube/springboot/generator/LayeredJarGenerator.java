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
import org.eclipse.jkube.springboot.SpringBootLayeredJar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static org.eclipse.jkube.kit.common.util.FileUtil.getRelativePath;

public class LayeredJarGenerator extends AbstractSpringBootNestedGenerator {

  private static final String DEPENDENCIES_LAYER = "dependencies";

  private final SpringBootLayeredJar springBootLayeredJar;
  private final File layeredJar;

  public LayeredJarGenerator(GeneratorContext generatorContext, GeneratorConfig generatorConfig, File layeredJar) {
    super(generatorContext, generatorConfig);
    this.layeredJar = layeredJar;
    springBootLayeredJar = new SpringBootLayeredJar(layeredJar, getLogger());
  }

  @Override
  public Map<String, String> getEnv(Function<Boolean, Map<String, String>> javaExecEnvSupplier, boolean prePackagePhase) {
    final Map<String, String> res = super.getEnv(javaExecEnvSupplier, prePackagePhase);
    res.put("JAVA_MAIN_CLASS", springBootLayeredJar.getMainClass());
    return res;
  }

  @Override
  public AssemblyConfiguration createAssemblyConfiguration(List<AssemblyFileSet> defaultFileSets) {
    getLogger().info("Spring Boot layered jar detected");
    final List<Assembly> layerAssemblies = new ArrayList<>();
    layerAssemblies.add(Assembly.builder().id("jkube-includes").fileSets(defaultFileSets).build());

    File buildPackageDirectory = getProject().getBuildPackageDirectory();
    getLogger().info("Build package directory: %s", buildPackageDirectory.getAbsolutePath());
    springBootLayeredJar.extractLayers(buildPackageDirectory);

    // tools jarmode extracts to <jar-basename>/<layer> subdirectory structure
    // layertools jarmode extracts directly to <layer> directories
    File layerBaseDir = findLayerBaseDirectory(buildPackageDirectory);
    getLogger().info("Layer base directory: %s", layerBaseDir.getAbsolutePath());

    // Each layer gets its own Assembly for Docker layer caching
    // but all files go to the same targetDir (flat runtime structure)
    for (String springBootLayer : springBootLayeredJar.listLayers()) {
      File layerDir = new File(layerBaseDir, springBootLayer);

      // Validate layer directory exists
      if (!layerDir.exists() || !layerDir.isDirectory()) {
        getLogger().error("Layer directory does not exist: %s", layerDir.getAbsolutePath());
        getLogger().error("Build package directory: %s", buildPackageDirectory.getAbsolutePath());
        getLogger().error("Layer base directory: %s", layerBaseDir.getAbsolutePath());
        if (buildPackageDirectory.exists()) {
          getLogger().error("Contents of build package directory: %s",
              String.join(", ", buildPackageDirectory.list() != null ?
                Objects.requireNonNull(buildPackageDirectory.list()) : new String[]{"<empty>"}));
        }
        throw new IllegalStateException(String.format(
            "Spring Boot layer directory '%s' does not exist. " +
            "Layers were expected in: %s. " +
            "This may indicate a mismatch between where layers were extracted and where they are being referenced.",
            layerDir.getAbsolutePath(),
            layerBaseDir.getAbsolutePath()
        ));
      }

      layerAssemblies.add(Assembly.builder()
              .id(springBootLayer)
              .fileSet(AssemblyFileSet.builder()
                  .directory(getRelativePath(getProject().getBaseDirectory(), layerDir))
                  .outputDirectory(new File("."))  // Flat: all layers → /deployments
                  .fileMode("0640")
                  .build())
          .build());
    }

    return AssemblyConfiguration.builder()
        .targetDir(getTargetDir())
        .excludeFinalOutputArtifact(true)
        .layers(layerAssemblies)
        .build();
  }

  /**
   * Find the base directory containing layer subdirectories.
   * tools jarmode extracts to <jar-basename>/<layer> structure,
   * while layertools jarmode extracts directly to <layer> directories.
   */
  private File findLayerBaseDirectory(File buildPackageDirectory) {
    // Check if layers exist directly in buildPackageDirectory (layertools behavior)
    if (new File(buildPackageDirectory, DEPENDENCIES_LAYER).exists()) {
      return buildPackageDirectory;
    }

    // Try to find subdirectory based on jar artifact name (tools jarmode behavior)
    // e.g., myapp-1.0.0.jar extracts to myapp-1.0.0/dependencies, myapp-1.0.0/spring-boot-loader, etc.
    String jarBaseName = getJarBaseName(layeredJar);
    if (jarBaseName != null) {
      File expectedSubdir = new File(buildPackageDirectory, jarBaseName);
      if (expectedSubdir.isDirectory() && new File(expectedSubdir, DEPENDENCIES_LAYER).exists()) {
        getLogger().debug("Found layers in artifact-specific subdirectory: %s", jarBaseName);
        return expectedSubdir;
      }
    }

    // Fallback: search all subdirectories for layers
    File[] subdirs = buildPackageDirectory.listFiles(File::isDirectory);
    if (subdirs != null) {
      for (File subdir : subdirs) {
        if (new File(subdir, DEPENDENCIES_LAYER).exists()) {
          getLogger().debug("Found layers in subdirectory: %s", subdir.getName());
          return subdir;
        }
      }
    }

    // Default to buildPackageDirectory if no layers found
    return buildPackageDirectory;
  }

  /**
   * Get the jar base name without extension.
   * e.g., "myapp-1.0.0.jar" → "myapp-1.0.0"
   */
  private String getJarBaseName(File jar) {
    if (jar == null) {
      return null;
    }
    String name = jar.getName();
    int lastDot = name.lastIndexOf('.');
    return lastDot > 0 ? name.substring(0, lastDot) : name;
  }
}
