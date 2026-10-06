// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadsTest {
    @Test void restrictsDownloadsToOfficialHttpsHosts() throws Exception {
        Downloads.validate(URI.create("https://api.github.com/repos/Pumpkin-MC/Pumpkin/releases/latest"));
        Downloads.validate(URI.create("https://release-assets.githubusercontent.com/asset?token=opaque"));
        for (String value : new String[]{"http://github.com/file", "https://github.com.evil.org/file",
                "file:///tmp/file", "https://user@github.com/file", "https://github.com:8080/file", "https://localhost/file"}) {
            assertThrows(IOException.class, () -> Downloads.validate(URI.create(value)), value);
        }
    }

    @Test void boundsStreamingResponsesEvenWithoutContentLength() throws Exception {
        byte[] content = new byte[128 * 1024];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> Downloads.copyLimited(new ByteArrayInputStream(content), output, 64 * 1024));
        assertEquals(64 * 1024, output.size());
        output.reset();
        Downloads.copyLimited(new ByteArrayInputStream(content), output, content.length);
        assertArrayEquals(content, output.toByteArray());
    }
}
