// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.util.Locale;
import java.util.Map;

record Platform(String id, String asset) {
    private static final Map<String, String> ASSETS = Map.of(
            "linux-x64-musl", "pumpkin-X64-Linux-musl",
            "linux-arm64-musl", "pumpkin-ARM64-Linux-musl",
            "linux-x64", "pumpkin-X64-Linux",
            "linux-arm64", "pumpkin-ARM64-Linux",
            "windows-x64", "pumpkin-X64-Windows.exe",
            "windows-arm64", "pumpkin-ARM64-Windows.exe",
            "macos-arm64", "pumpkin-ARM64-macOS");

    static Platform select(String requested, String osName, String architecture) {
        String id = requested;
        if (id.equals("auto")) {
            String arch = switch (architecture.toLowerCase(Locale.ROOT)) {
                case "amd64", "x86_64", "x64" -> "x64";
                case "aarch64", "arm64" -> "arm64";
                default -> throw new IllegalArgumentException("Unsupported CPU architecture: " + architecture);
            };
            String os = osName.toLowerCase(Locale.ROOT);
            if (os.startsWith("windows")) id = "windows-" + arch;
            else if (os.equals("linux")) id = "linux-" + arch + "-musl";
            else if (os.contains("mac") || os.contains("darwin")) id = "macos-" + arch;
            else throw new IllegalArgumentException("Unsupported operating system: " + osName);
        }
        String asset = ASSETS.get(id);
        if (asset == null) {
            throw new IllegalArgumentException("No official binary for platform '" + id
                    + "'. Supported: " + String.join(", ", ASSETS.keySet().stream().sorted().toList())
                    + ". You can also set binary to your own compatible Pumpkin executable.");
        }
        return new Platform(id, asset);
    }
}
