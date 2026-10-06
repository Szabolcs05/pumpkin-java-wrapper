// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InstallerTest {
    @TempDir Path directory;
    private static final String ASSET = "pumpkin-X64-Linux-musl";

    private Settings settings(String release, String pin) { return new Settings(release, "linux-x64-musl", "", pin, 30); }
    private Path cache() { return directory.resolve("cache"); }

    private String hash(String content) throws IOException {
        Path file = Files.createTempFile(directory, "hash-", ".txt");
        Files.writeString(file, content);
        return Installer.sha256(file);
    }

    private static String metadata(String digest) {
        return """
                {"tag_name":"0.2.0+26.3-26.51","assets":[{"name":"%s",
                "digest":%s,"browser_download_url":"https://github.com/Pumpkin-MC/Pumpkin/releases/download/test/%s"}]}
                """.formatted(ASSET, digest == null ? "null" : "\"sha256:" + digest + "\"", ASSET);
    }

    @Test void installsVerifiesAndStartsOfflineFromCache() throws Exception {
        String digest = hash("a native executable fixture");
        AtomicInteger requests = new AtomicInteger();
        Installer installer = new Installer((uri, path, max) -> {
            requests.incrementAndGet();
            Files.writeString(path, uri.getHost().equals("api.github.com") ? metadata(digest) : "a native executable fixture");
        });
        Path binary = installer.resolve(settings("latest", ""), directory, cache(), false);
        assertEquals(digest, Installer.sha256(binary));
        assertEquals(2, requests.get());
        Installer offline = new Installer((uri, path, max) -> { throw new AssertionError("Cached launch accessed network"); });
        assertEquals(binary, offline.resolve(settings("latest", ""), directory, cache(), false));
        assertEquals(1, manifests());
    }

    @Test void rejectsCorruptDownloadWithoutPublishingInstallation() throws Exception {
        String digest = hash("expected");
        Installer installer = new Installer((uri, path, max) ->
                Files.writeString(path, uri.getHost().equals("api.github.com") ? metadata(digest) : "corrupt"));
        assertThrows(IOException.class, () -> installer.resolve(settings("latest", ""), directory, cache(), false));
        assertEquals(0, manifests());
        try (var paths = Files.walk(cache())) {
            assertTrue(paths.noneMatch(p -> p.toString().endsWith(".part")));
        }
    }

    @Test void tamperedCacheIsNotExecutedOrSilentlyRepaired() throws Exception {
        String digest = hash("original");
        Installer installer = fixture(digest, "original");
        Path binary = installer.resolve(settings("latest", ""), directory, cache(), false);
        Files.writeString(binary, "tampered");
        Installer offline = new Installer((uri, path, max) -> { throw new AssertionError("Tamper repair must be explicit"); });
        IOException error = assertThrows(IOException.class, () -> offline.resolve(settings("latest", ""), directory, cache(), false));
        assertTrue(error.getMessage().contains("SHA-256 mismatch"));
    }

    @Test void failedUpdatePreservesPreviousInstallation() throws Exception {
        String firstHash = hash("first");
        Path binary = fixture(firstHash, "first").resolve(settings("latest", ""), directory, cache(), false);
        String secondHash = hash("second");
        assertThrows(IOException.class, () -> fixture(secondHash, "truncated").resolve(settings("latest", ""), directory, cache(), true));
        Installer offline = new Installer((uri, path, max) -> { throw new AssertionError("Network used"); });
        assertEquals(binary, offline.resolve(settings("latest", ""), directory, cache(), false));
        assertEquals("first", Files.readString(binary));
    }

    @Test void explicitUpdateChangesSelectionAndRetainsOldBinary() throws Exception {
        Path first = fixture(hash("first"), "first").resolve(settings("latest", ""), directory, cache(), false);
        String secondHash = hash("second");
        Path second = fixture(secondHash, "second").resolve(settings("latest", ""), directory, cache(), true);
        assertNotEquals(first, second);
        assertEquals("first", Files.readString(first));
        assertEquals(secondHash, Installer.sha256(second));
        assertEquals(1, manifests());
    }

    @Test void changedSelectorMakesSeparateInstallationAndEscapesTag() throws Exception {
        String digest = hash("native");
        fixture(digest, "native").resolve(settings("latest", ""), directory, cache(), false);
        AtomicInteger metadataRequests = new AtomicInteger();
        Installer installer = new Installer((uri, path, max) -> {
            assertTrue(uri.toString().endsWith("tags/v1%2Btest%2Ftag"));
            metadataRequests.incrementAndGet();
            Files.writeString(path, metadata(digest));
        });
        installer.resolve(settings("v1+test/tag", ""), directory, cache(), false);
        assertEquals(1, metadataRequests.get());
        assertEquals(2, manifests());
    }

    @Test void honorsChecksumPinOnDownloadAndCache() throws Exception {
        String digest = hash("native"), wrong = "0".repeat(64);
        Installer installer = fixture(digest, "native");
        assertThrows(IOException.class, () -> installer.resolve(settings("latest", wrong), directory, cache(), false));
        installer.resolve(settings("latest", digest), directory, cache(), false);
        assertThrows(IOException.class, () -> installer.resolve(settings("latest", wrong), directory, cache(), false));
    }

    @Test void localBinaryNeedsNoNetworkAndCanHaveAnIndependentHashPin() throws Exception {
        Path local = directory.resolve("custom binary");
        Files.writeString(local, "locally built");
        Installer offline = new Installer((uri, path, max) -> { throw new AssertionError("Network used"); });
        Settings settings = new Settings("latest", "unsupported", "custom binary", Installer.sha256(local), 30);
        assertEquals(local, offline.resolve(settings, directory, cache(), false));
        assertThrows(IllegalArgumentException.class, () -> offline.resolve(settings, directory, cache(), true));
        Files.writeString(local, "changed");
        assertThrows(IOException.class, () -> offline.resolve(settings, directory, cache(), false));
    }

    @Test void requiresOfficialAssetWithValidDigest() throws Exception {
        String digest = "a".repeat(64);
        assertEquals(digest, Installer.parseRelease(metadata(digest), ASSET).sha256());
        assertThrows(IOException.class, () -> Installer.parseRelease(metadata(null), ASSET));
        assertThrows(IOException.class, () -> Installer.parseRelease(metadata("../escape"), ASSET));
        assertThrows(IOException.class, () -> Installer.parseRelease(metadata(digest), "missing"));
        assertThrows(IOException.class, () -> Installer.parseRelease("not json", ASSET));
        assertThrows(IOException.class, () -> Installer.parseRelease(metadata(digest).replace("github.com/Pumpkin-MC", "example.org/Pumpkin-MC"), ASSET));
        assertThrows(IOException.class, () -> Installer.parseRelease(metadata(digest).replace("/Pumpkin-MC/Pumpkin/", "/attacker/Pumpkin/"), ASSET));
    }

    private Installer fixture(String digest, String content) {
        return new Installer((uri, path, max) -> Files.writeString(path, uri.getHost().equals("api.github.com") ? metadata(digest) : content));
    }

    private long manifests() throws IOException {
        try (var paths = Files.list(cache())) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".properties")).count();
        }
    }
}
