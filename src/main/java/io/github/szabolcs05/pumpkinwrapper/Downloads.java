// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** HTTPS only, bounded redirects, response sizes, and connect/read timeouts. */
final class Downloads {
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    private Downloads() { }

    static void fetch(URI uri, Path destination, long maxBytes) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            validate(uri);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Pumpkin-Java-Wrapper (github.com/Szabolcs05/pumpkin-java-wrapper)");
            connection.setRequestProperty("Accept", "api.github.com".equals(uri.getHost())
                    ? "application/vnd.github+json" : "application/octet-stream");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Download redirect has no Location header.");
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("GitHub returned HTTP " + status + " for " + uri.getHost()
                            + ". Check the release tag, outbound HTTPS access, and GitHub API rate limits."
                            + " A verified cached binary or the binary setting allows offline starts.");
                }
                if (connection.getContentLengthLong() > maxBytes) throw new IOException("Download exceeds size limit.");
                try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(destination)) {
                    copyLimited(input, output, maxBytes);
                }
                return;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many GitHub download redirects.");
    }

    static void validate(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOSTS.contains(uri.getHost())
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new IOException("Refusing download outside official GitHub HTTPS hosts.");
        }
    }

    static void copyLimited(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            size += count;
            if (size > maximum) throw new IOException("Download exceeds size limit.");
            output.write(buffer, 0, count);
        }
    }
}
