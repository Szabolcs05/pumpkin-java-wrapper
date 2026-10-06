// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"Linux,amd64,linux-x64-musl", "Linux,aarch64,linux-arm64-musl",
            "Windows 11,amd64,windows-x64", "Windows Server 2022,arm64,windows-arm64",
            "Mac OS X,aarch64,macos-arm64", "Darwin,arm64,macos-arm64"})
    void detectsPlatforms(String os, String arch, String id) {
        assertEquals(id, Platform.select("auto", os, arch).id());
    }

    @Test void rejectsUnsupportedAutoPlatformsAndAllowsExplicitGlibc() {
        assertThrows(IllegalArgumentException.class, () -> Platform.select("auto", "Linux", "riscv64"));
        assertThrows(IllegalArgumentException.class, () -> Platform.select("auto", "Mac OS X", "x86_64"));
        assertThrows(IllegalArgumentException.class, () -> Platform.select("auto", "FreeBSD", "amd64"));
        assertEquals("pumpkin-X64-Linux", Platform.select("linux-x64", "Linux", "amd64").asset());
    }

    @Test void createsConfigOnceAndHonorsEnvironmentOverrides() throws Exception {
        Path config = directory.resolve("nested/config.properties");
        Settings defaults = Settings.load(config, Map.of());
        assertEquals("latest", defaults.release());
        assertEquals(30, defaults.shutdownTimeoutSeconds());
        Files.writeString(config, "release=version1\nbinary=bin/Pumpkin\nshutdown-timeout-seconds=45\n");
        Settings custom = Settings.load(config, Map.of("PUMPKIN_WRAPPER_RELEASE", "canary", "PUMPKIN_WRAPPER_SHUTDOWN_TIMEOUT_SECONDS", "60"));
        assertEquals("canary", custom.release());
        assertEquals("bin/Pumpkin", custom.binary());
        assertEquals(60, custom.shutdownTimeoutSeconds());
        assertTrue(Files.readString(config).contains("version1"));
    }

    @Test void validatesSettingsInsteadOfSilentlyIgnoringMistakes() throws Exception {
        Path config = directory.resolve("config.properties");
        for (String bad : List.of("release=", "sha256=wrong", "shutdown-timeout-seconds=0",
                "shutdown-timeout-seconds=301", "shutdown-timeout-seconds=abc", "relase=typo")) {
            Files.writeString(config, bad);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(config, Map.of()), bad);
        }
    }

    @Test void parsesHostingArgumentsAndExplicitPassthrough() {
        Main.Options args = Main.Options.parse(new String[]{"nogui", "--nogui", "--wrapper-update",
                "--wrapper-download-only", "--wrapper-config", "path with spaces.properties", "--", "--help", "nogui", "a b"});
        assertTrue(args.update());
        assertTrue(args.downloadOnly());
        assertFalse(args.help());
        assertEquals(Path.of("path with spaces.properties"), args.config());
        assertEquals(List.of("--help", "nogui", "a b"), args.serverArgs());
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"--wrapper-typo"}));
        assertThrows(IllegalArgumentException.class, () -> Main.Options.parse(new String[]{"--wrapper-config"}));
    }
}
