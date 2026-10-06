// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

record Settings(String release, String platform, String binary, String sha256, int shutdownTimeoutSeconds) {
    static final String DEFAULTS = """
            # Pumpkin Java Wrapper configuration (UTF-8).
            # Paths are relative to the server working directory. Use / in Windows paths.
            # latest means the latest stable release on FIRST install; cached starts stay pinned.
            # Use an exact upstream tag to pin a version, or canary to opt into development builds.
            release=latest
            # Linux defaults to musl for broad hosting compatibility.
            platform=auto
            # Optional: your own native executable; bypasses all network downloads.
            binary=
            # Optional extra SHA-256 pin. Official downloads always require GitHub's SHA-256.
            sha256=
            # Time to allow Pumpkin to save and stop when the JVM is terminated.
            shutdown-timeout-seconds=30
            """;
    private static final Set<String> KEYS = Set.of("release", "platform", "binary", "sha256", "shutdown-timeout-seconds");

    static Settings load(Path file, Map<String, String> environment) throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        for (String key : properties.stringPropertyNames()) {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown setting in " + file + ": " + key);
        }
        String release = value(properties, environment, "release", "latest");
        if (release.isBlank()) throw new IllegalArgumentException("release must be latest, canary, or an upstream release tag.");
        String digest = value(properties, environment, "sha256", "").toLowerCase(java.util.Locale.ROOT);
        if (!digest.isEmpty() && !digest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be empty or exactly 64 hexadecimal characters.");
        }
        int timeout;
        try {
            timeout = Integer.parseInt(value(properties, environment, "shutdown-timeout-seconds", "30"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("shutdown-timeout-seconds must be an integer between 1 and 300.");
        }
        if (timeout < 1 || timeout > 300) throw new IllegalArgumentException("shutdown-timeout-seconds must be between 1 and 300.");
        return new Settings(release, value(properties, environment, "platform", "auto"),
                value(properties, environment, "binary", ""), digest, timeout);
    }

    private static String value(Properties properties, Map<String, String> environment, String key, String fallback) {
        String envKey = "PUMPKIN_WRAPPER_" + key.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        return environment.getOrDefault(envKey, properties.getProperty(key, fallback)).trim();
    }
}
