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

import lombok.Getter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Utility class for managing CA certificates installation in container images.
 * Provides runtime-specific commands for installing self-signed CA certificates
 * based on the base image's operating system and runtime.
 *
 * @author JKube Team
 */
public class CACertificateManager {

    private static final Pattern ALPINE_PATTERN = Pattern.compile("alpine", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIAN_UBUNTU_PATTERN = Pattern.compile("(debian|ubuntu)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REDHAT_PATTERN = Pattern.compile("(rhel|ubi|centos|fedora)", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVA_PATTERN = Pattern.compile("(java|openjdk|jdk|jre|eclipse-temurin)", Pattern.CASE_INSENSITIVE);

    private CACertificateManager() {
        // Utility class
    }

    /**
     * Generates RUN commands to install CA certificates based on the base image.
     *
     * @param baseImage The base image name (e.g., "openjdk:11-jre-slim", "node:16-alpine")
     * @param certPaths List of certificate file paths to install
     * @return List of RUN commands to add to the Dockerfile
     */
    public static List<String> generateCertInstallCommands(String baseImage, List<String> certPaths) {
        if (baseImage == null || certPaths == null || certPaths.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> commands = new ArrayList<>();
        ImageRuntime runtime = detectRuntime(baseImage);

        // Add commands based on OS type
        if (ALPINE_PATTERN.matcher(baseImage).find()) {
            commands.addAll(generateAlpineCertCommands(certPaths, runtime));
        } else if (DEBIAN_UBUNTU_PATTERN.matcher(baseImage).find()) {
            commands.addAll(generateDebianUbuntuCertCommands(certPaths, runtime));
        } else if (REDHAT_PATTERN.matcher(baseImage).find()) {
            commands.addAll(generateRedHatCertCommands(certPaths, runtime));
        } else {
            // Default to Debian-based commands as it's most common
            commands.addAll(generateDebianUbuntuCertCommands(certPaths, runtime));
        }

        return commands;
    }

    /**
     * Generates the renamed certificate filename that will be used in the Docker build context.
     *
     * @param certPath Original certificate file path
     * @param index Index of the certificate in the list
     * @return The renamed filename to be used in the Docker build context
     */
    public static String getCertificateFilenameInBuildContext(String certPath, int index) {
        File certFile = new File(certPath);
        return "cert-" + index + "-" + certFile.getName();
    }

    /**
     * Generates COPY commands for certificate files.
     *
     * @param certPaths List of certificate file paths
     * @param targetDir Target directory in the container
     * @return List of COPY source-destination pairs
     */
    public static List<CopyCertEntry> generateCopyCertEntries(List<String> certPaths, String targetDir) {
        if (certPaths == null || certPaths.isEmpty()) {
            return Collections.emptyList();
        }

        List<CopyCertEntry> entries = new ArrayList<>();
        for (int i = 0; i < certPaths.size(); i++) {
            String certPath = certPaths.get(i);
            // Use only the filename for source (relative to Dockerfile), not the absolute path
            String sourceInDockerContext = getCertificateFilenameInBuildContext(certPath, i);
            entries.add(new CopyCertEntry(sourceInDockerContext, targetDir + "/" + sourceInDockerContext));
        }
        return entries;
    }

    private static ImageRuntime detectRuntime(String baseImage) {
        if (JAVA_PATTERN.matcher(baseImage).find()) {
            return ImageRuntime.JAVA;
        }
        return ImageRuntime.GENERIC;
    }

    private static List<String> generateAlpineCertCommands(List<String> certPaths, ImageRuntime runtime) {
        List<String> commands = new ArrayList<>();

        // Copy certificates to Alpine's CA certificate directory
        for (int i = 0; i < certPaths.size(); i++) {
            File certFile = new File(certPaths.get(i));
            String certFileName = "cert-" + i + "-" + certFile.getName();
            commands.add("cp /tmp/certs/" + certFileName + " /usr/local/share/ca-certificates/");
        }

        // Update system CA certificates
        commands.add("update-ca-certificates");

        // Runtime-specific certificate installation
        if (runtime == ImageRuntime.JAVA) {
            commands.addAll(generateJavaCertCommands(certPaths));
        }

        return commands;
    }

    private static List<String> generateDebianUbuntuCertCommands(List<String> certPaths, ImageRuntime runtime) {
        List<String> commands = new ArrayList<>();

        // Copy certificates to Debian/Ubuntu's CA certificate directory
        for (int i = 0; i < certPaths.size(); i++) {
            File certFile = new File(certPaths.get(i));
            String certFileName = "cert-" + i + "-" + certFile.getName();
            commands.add("cp /tmp/certs/" + certFileName + " /usr/local/share/ca-certificates/");
        }

        // Update system CA certificates
        commands.add("update-ca-certificates");

        // Runtime-specific certificate installation
        if (runtime == ImageRuntime.JAVA) {
            commands.addAll(generateJavaCertCommands(certPaths));
        }

        return commands;
    }

    private static List<String> generateRedHatCertCommands(List<String> certPaths, ImageRuntime runtime) {
        List<String> commands = new ArrayList<>();

        // Copy certificates to Red Hat's CA certificate directory
        for (int i = 0; i < certPaths.size(); i++) {
            File certFile = new File(certPaths.get(i));
            String certFileName = "cert-" + i + "-" + certFile.getName();
            commands.add("cp /tmp/certs/" + certFileName + " /etc/pki/ca-trust/source/anchors/");
        }

        // Update system CA certificates
        commands.add("update-ca-trust");

        // Runtime-specific certificate installation
        if (runtime == ImageRuntime.JAVA) {
            commands.addAll(generateJavaCertCommands(certPaths));
        }

        return commands;
    }

    private static List<String> generateJavaCertCommands(List<String> certPaths) {
        List<String> commands = new ArrayList<>();

        // Import certificates into Java truststore
        for (int i = 0; i < certPaths.size(); i++) {
            File certFile = new File(certPaths.get(i));
            String certFileName = "cert-" + i + "-" + certFile.getName();
            String alias = "jkube-cert-" + i;

            // Try to find Java's cacerts file in common locations
            commands.add(String.format(
                "keytool -importcert -noprompt -trustcacerts -alias %s -file /tmp/certs/%s " +
                "-keystore $JAVA_HOME/lib/security/cacerts -storepass changeit || " +
                "keytool -importcert -noprompt -trustcacerts -alias %s -file /tmp/certs/%s " +
                "-keystore $JAVA_HOME/jre/lib/security/cacerts -storepass changeit || true",
                alias, certFileName, alias, certFileName
            ));
        }

        return commands;
    }

    /**
     * Represents a certificate copy entry with source and destination.
     */
    @Getter
    public static class CopyCertEntry {
        private final String source;
        private final String destination;

        public CopyCertEntry(String source, String destination) {
            this.source = source;
            this.destination = destination;
        }

    }

    /**
     * Enum representing different container runtime types.
     */
    private enum ImageRuntime {
        JAVA,
        GENERIC
    }
}
