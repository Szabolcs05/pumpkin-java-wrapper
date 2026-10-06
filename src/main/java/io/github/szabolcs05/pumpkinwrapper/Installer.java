// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Properties;

final class Installer {
    static final String REPOSITORY = "https://github.com/Pumpkin-MC/Pumpkin";
    private static final long MAX_BINARY = 1024L * 1024 * 1024;
    private final Fetcher fetcher;

    @FunctionalInterface
    interface Fetcher { void fetch(URI uri, Path destination, long maximum) throws IOException; }
    record Asset(String tag, String name, URI url, String sha256) { }

    Installer() { this(Downloads::fetch); }
    Installer(Fetcher fetcher) { this.fetcher = fetcher; }

    Path resolve(Settings settings, Path directory, Path cache, boolean update) throws IOException {
        if (!settings.binary().isEmpty()) {
            if (update) throw new IllegalArgumentException("--wrapper-update cannot be used with a local binary setting.");
            Path local = directory.resolve(settings.binary()).normalize().toAbsolutePath();
            if (!Files.isRegularFile(local)) throw new IOException("Local Pumpkin binary does not exist: " + local);
            if (!settings.sha256().isEmpty()) verify(local, settings.sha256());
            executable(local);
            return local;
        }
        Platform platform = Platform.select(settings.platform(), System.getProperty("os.name"), System.getProperty("os.arch"));
        Files.createDirectories(cache);
        String key = digest((settings.release() + "\n" + platform.asset()).getBytes(StandardCharsets.UTF_8));
        Path manifest = cache.resolve("selection-" + key + ".properties");
        if (Files.exists(manifest) && !update) {
            Properties saved = new Properties();
            try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) { saved.load(reader); }
            String hash = saved.getProperty("sha256", "");
            requireHash(hash);
            checkPin(settings.sha256(), hash);
            Path binary = binaryPath(cache, hash, platform);
            verify(binary, hash);
            executable(binary);
            Main.log("Using cached Pumpkin " + saved.getProperty("release", "unknown") + " (" + platform.id() + ").");
            return binary;
        }
        Path metadata = Files.createTempFile(cache, "release-", ".json");
        try {
            String selector = settings.release().equals("latest") ? "latest" : "tags/" + encode(settings.release());
            fetcher.fetch(URI.create("https://api.github.com/repos/Pumpkin-MC/Pumpkin/releases/" + selector), metadata, 4 * 1024 * 1024);
            Asset asset = parseRelease(Files.readString(metadata), platform.asset());
            checkPin(settings.sha256(), asset.sha256());
            Path binary = binaryPath(cache, asset.sha256(), platform);
            if (Files.exists(binary)) {
                verify(binary, asset.sha256());
            } else {
                Files.createDirectories(binary.getParent());
                Path partial = Files.createTempFile(binary.getParent(), "download-", ".part");
                try {
                    Main.log("Downloading official Pumpkin " + asset.tag() + " / " + asset.name() + "...");
                    fetcher.fetch(asset.url(), partial, MAX_BINARY);
                    verify(partial, asset.sha256());
                    executable(partial);
                    replace(partial, binary);
                } finally {
                    Files.deleteIfExists(partial);
                }
            }
            executable(binary);
            Properties saved = new Properties();
            saved.setProperty("release", asset.tag());
            saved.setProperty("requested-release", settings.release());
            saved.setProperty("asset", asset.name());
            saved.setProperty("sha256", asset.sha256());
            saved.setProperty("download", asset.url().toString());
            saved.setProperty("source", REPOSITORY + "/tree/" + encode(asset.tag()));
            Path temporaryManifest = Files.createTempFile(cache, "selection-", ".tmp");
            try {
                try (Writer writer = Files.newBufferedWriter(temporaryManifest, StandardCharsets.UTF_8)) {
                    saved.store(writer, "Verified Pumpkin installation. Do not edit; use --wrapper-update to update.");
                }
                replace(temporaryManifest, manifest);
            } finally { Files.deleteIfExists(temporaryManifest); }
            Main.log("Verified SHA-256: " + asset.sha256());
            return binary;
        } finally { Files.deleteIfExists(metadata); }
    }

    static Asset parseRelease(String json, String wanted) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String tag = root.get("tag_name").getAsString();
            if (tag.isBlank()) throw new IOException("GitHub release has an empty tag.");
            for (JsonElement element : root.getAsJsonArray("assets")) {
                JsonObject asset = element.getAsJsonObject();
                if (!wanted.equals(asset.get("name").getAsString())) continue;
                JsonElement digest = asset.get("digest");
                if (digest == null || digest.isJsonNull() || !digest.getAsString().startsWith("sha256:")) {
                    throw new IOException("The official asset has no SHA-256 digest. Use a newer release, or set binary and sha256 for a manually verified executable.");
                }
                String hash = digest.getAsString().substring(7);
                requireHash(hash);
                URI uri = URI.create(asset.get("browser_download_url").getAsString());
                Downloads.validate(uri);
                if (!"github.com".equals(uri.getHost()) || !uri.getPath().startsWith("/Pumpkin-MC/Pumpkin/releases/download/")) {
                    throw new IOException("Asset URL does not belong to the official Pumpkin repository.");
                }
                return new Asset(tag, wanted, uri, hash);
            }
            throw new IOException("Release " + tag + " has no asset named " + wanted + ". Choose a supported platform or local binary.");
        } catch (JsonParseException | IllegalStateException | IllegalArgumentException | NullPointerException | UnsupportedOperationException e) {
            throw new IOException("Invalid GitHub release metadata: " + e.getMessage(), e);
        }
    }

    private static Path binaryPath(Path cache, String hash, Platform platform) {
        return cache.resolve("binaries").resolve(hash).resolve(platform.asset().endsWith(".exe") ? "pumpkin.exe" : "pumpkin");
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static void verify(Path file, String expected) throws IOException {
        requireHash(expected);
        if (!Files.isRegularFile(file)) throw new IOException("Cached binary is missing: " + file + ". Run --wrapper-update to reinstall.");
        if (!sha256(file).equals(expected)) {
            throw new IOException("SHA-256 mismatch for " + file + ". Refusing to execute it."
                    + " Remove the affected cached binary and run --wrapper-update to reinstall; check any configured sha256 pin.");
        }
    }

    private static void checkPin(String pin, String actual) throws IOException {
        if (!pin.isEmpty() && !pin.equals(actual)) throw new IOException("Official/cached SHA-256 does not match your configured sha256 pin.");
    }

    private static void requireHash(String hash) throws IOException {
        if (!hash.matches("[0-9a-f]{64}")) throw new IOException("Missing or invalid SHA-256 digest; refusing to install or run the binary.");
    }

    private static String digest(byte[] bytes) { return HexFormat.of().formatHex(sha256Digest().digest(bytes)); }
    private static MessageDigest sha256Digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static void executable(Path file) throws IOException {
        if (!file.toFile().setExecutable(true, true) && !Files.isExecutable(file)) {
            throw new IOException("Cannot mark binary executable: " + file + ". Check hosting permissions and noexec mounts.");
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException e) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
}
