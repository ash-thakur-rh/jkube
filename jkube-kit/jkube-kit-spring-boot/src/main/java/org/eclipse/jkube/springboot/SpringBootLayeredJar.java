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
package org.eclipse.jkube.springboot;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jkube.kit.common.ExternalCommand;
import org.eclipse.jkube.kit.common.KitLogger;
import org.eclipse.jkube.kit.common.util.Serialization;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;

public class SpringBootLayeredJar {

  private final File layeredJar;
  private final KitLogger kitLogger;

  public SpringBootLayeredJar(File layeredJar, KitLogger kitLogger) {
    this.layeredJar = layeredJar;
    this.kitLogger = kitLogger;
  }

  public boolean isLayeredJar() {
    try (JarFile jarFile = new JarFile(layeredJar)) {
      return jarFile.getEntry("BOOT-INF/layers.idx") != null && StringUtils.isNotBlank(getMainClass());
    } catch(Exception e) {
      kitLogger.debug("Couldn't determine if Spring Boot jar %s is layered", layeredJar.getName(), e);
    }
    return false;
  }

  public String getMainClass() {
    try (JarFile jarFile = new JarFile(layeredJar)) {
      final ZipEntry manifest = jarFile.getEntry("META-INF/MANIFEST.MF");
      if (manifest == null) {
        return null;
      }
      final Properties properties = new Properties();
      try (InputStream manifestInputStream = jarFile.getInputStream(manifest)) {
        properties.load(manifestInputStream);
        return properties.getProperty("Main-Class");
      }
    } catch(Exception e) {
      kitLogger.debug("Couldn't determine Spring Boot jar's (%s) main class ", layeredJar.getName(), e);
    }
    return null;
  }

  public Optional<String> getSpringBootVersion() {
    try (JarFile jarFile = new JarFile(layeredJar)) {
      final ZipEntry manifest = jarFile.getEntry("META-INF/MANIFEST.MF");
      if (manifest == null) {
        return Optional.empty();
      }
      final Properties properties = new Properties();
      try (InputStream manifestInputStream = jarFile.getInputStream(manifest)) {
        properties.load(manifestInputStream);
        return Optional.ofNullable(properties.getProperty("Spring-Boot-Version"));
      }
    } catch(Exception e) {
      kitLogger.debug("Couldn't determine Spring Boot jar's (%s) version ", layeredJar.getName(), e);
    }
    return Optional.empty();
  }

  public List<String> listLayers() {
    try (JarFile jarFile = new JarFile(layeredJar)) {
      List<Map<String, List<String>>> layers = Serialization.unmarshal(jarFile.getInputStream(jarFile.getEntry("BOOT-INF/layers.idx")), List.class);
      if (layers == null) {
        throw new IOException("Unable to find layers information in BOOT-INF/layers.idx file");
      }

      return layers.stream()
          .flatMap(m -> m.keySet().stream())
          .collect(Collectors.toList());
    } catch (IOException ioException) {
      throw new IllegalStateException("Failure in getting spring boot jar layers information", ioException);
    }
  }

  public void extractLayers(File extractionDir) {
    // Try manual extraction first (works on any JDK)
    try {
      extractLayersManually(extractionDir);
      kitLogger.info("Extracted Spring Boot layers using direct JAR extraction");
      return;
    } catch (Exception e) {
      kitLogger.debug("Manual layer extraction failed, falling back to jarmode: %s", e.getMessage());
    }

    // Fallback: execute jarmode (requires compatible JDK)
    String jarMode = determineJarMode();
    if (jarMode != null) {
      try {
        String[] extractArgs = getExtractArgs(jarMode);
        new LayerToolsCommand(kitLogger, extractionDir, layeredJar, jarMode, extractArgs).execute();
        kitLogger.info("Extracted Spring Boot layers using jarmode=%s", jarMode);
        return;
      } catch (IOException ioException) {
        kitLogger.debug("Failed with jarmode=%s: %s", jarMode, ioException.getMessage());
      }
    }

    // Final fallback: try both jarmodes
    IOException lastException = null;
    for (String fallbackJarMode : new String[]{"tools", "layertools"}) {
      try {
        kitLogger.debug("Trying jarmode=%s for layer extraction", fallbackJarMode);
        String[] extractArgs = getExtractArgs(fallbackJarMode);
        new LayerToolsCommand(kitLogger, extractionDir, layeredJar, fallbackJarMode, extractArgs).execute();
        return;
      } catch (IOException ioException) {
        kitLogger.debug("Failed with jarmode=%s: %s", fallbackJarMode, ioException.getMessage());
        lastException = ioException;
      }
    }
    throw new IllegalStateException("Failure in extracting spring boot jar layers", lastException);
  }

  // Package-private for testing
  String[] getExtractArgs(String jarMode) {
    // tools jarmode requires --layers flag to produce layered directory structure
    // layertools jarmode only supports extract command without flags
    return "tools".equals(jarMode)
        ? new String[]{"extract", "--layers"}
        : new String[]{"extract"};
  }

  // Package-private for testing
  boolean isVersion330OrNewer(String version) {
    try {
      String[] parts = version.split("[.-]");
      if (parts.length < 2) {
        return false;
      }
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);

      return major > 3 || (major == 3 && minor >= 3);
    } catch (NumberFormatException e) {
      kitLogger.debug("Unable to parse Spring Boot version: %s", version, e);
      return false;
    }
  }

  // Package-private for testing
  String determineJarMode() {
    Optional<String> version = getSpringBootVersion();
    if (version.isPresent() && isVersion330OrNewer(version.get())) {
      return "tools";
    } else if (version.isPresent()) {
      return "layertools";
    }
    return null;
  }

  /**
   * Manually extract layers by reading the JAR directly.
   * This works on any JDK version, unlike jarmode execution which requires
   * the same JDK version as the application's target.
   */
  private void extractLayersManually(File extractionDir) throws IOException {
    try (JarFile jarFile = new JarFile(layeredJar)) {
      // 1. Read layers.idx to get layer patterns
      Map<String, List<String>> layerPatterns = readLayerPatterns(jarFile);

      // 2. Create empty layer directories (even for layers with no files)
      for (String layerName : layerPatterns.keySet()) {
        new File(extractionDir, layerName).mkdirs();
      }

      // 3. Extract each JAR entry to the appropriate layer directory
      java.util.Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();

        if (entry.isDirectory()) {
          continue;
        }

        String layerName = findMatchingLayer(entry.getName(), layerPatterns);
        if (layerName != null) {
          File outputFile = new File(extractionDir, layerName + File.separator + entry.getName());
          outputFile.getParentFile().mkdirs();

          try (InputStream in = jarFile.getInputStream(entry);
               FileOutputStream out = new FileOutputStream(outputFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
              out.write(buffer, 0, bytesRead);
            }
          }
        }
      }
    }
  }

  /**
   * Read layers.idx and parse into a map of layer name to path patterns.
   */
  private Map<String, List<String>> readLayerPatterns(JarFile jarFile) throws IOException {
    ZipEntry layersIdxEntry = jarFile.getEntry("BOOT-INF/layers.idx");
    if (layersIdxEntry == null) {
      throw new IOException("BOOT-INF/layers.idx not found in JAR");
    }

    List<Map<String, List<String>>> layers = Serialization.unmarshal(
        jarFile.getInputStream(layersIdxEntry),
        List.class
    );

    if (layers == null) {
      throw new IOException("Unable to parse BOOT-INF/layers.idx");
    }

    // Convert list of maps to single map: layer name -> patterns
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Map<String, List<String>> layer : layers) {
      result.putAll(layer);
    }

    return result;
  }

  /**
   * Find which layer a JAR entry belongs to based on path patterns.
   * Returns the layer name, or null if no match.
   */
  private String findMatchingLayer(String entryName, Map<String, List<String>> layerPatterns) {
    for (Map.Entry<String, List<String>> layerEntry : layerPatterns.entrySet()) {
      String layerName = layerEntry.getKey();
      List<String> patterns = layerEntry.getValue();

      if (patterns == null) {
        continue;
      }

      for (String pattern : patterns) {
        if (entryName.startsWith(pattern)) {
          return layerName;
        }
      }
    }

    return null;
  }

  private static class LayerToolsCommand extends ExternalCommand {
    private final File layeredJar;
    private final String[] args;
    private final String jarMode;

    protected LayerToolsCommand(KitLogger log, File workDir, File layeredJar, String jarMode, String... args) {
      super(log, workDir);
      this.layeredJar = layeredJar;
      this.jarMode = jarMode;
      this.args = args;
    }

    @Override
    protected String[] getArgs() {
      return ArrayUtils.addAll(new String[] { "java", "-Djarmode=" + jarMode, "-jar", layeredJar.getAbsolutePath()}, args);
    }
  }

}
