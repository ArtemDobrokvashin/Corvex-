package ru.corvex.mailstorage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawMessageSpoolAdditionalTest {

    @TempDir
    Path tmp;

    @Test
    void supportsExistsSizeDeleteAndExactSizeLimit() throws Exception {
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 5);

        RawMessageSpool.StoredRawMessage stored = spool.store(
                new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)));

        assertTrue(spool.exists(stored.rawRef()));
        assertEquals(5, spool.size(stored.rawRef()));
        spool.delete(stored.rawRef());
        spool.delete(stored.rawRef());
        assertFalse(spool.exists(stored.rawRef()));
        assertThrows(NoSuchFileException.class, () -> spool.size(stored.rawRef()));
    }

    @Test
    void failedStoreRemovesPartialFile() throws Exception {
        Path root = tmp.resolve("spool");
        RawMessageSpool spool = new RawMessageSpool(root, 1024);
        InputStream broken = new InputStream() {
            private boolean emitted;

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (!emitted) {
                    emitted = true;
                    b[off] = 'x';
                    return 1;
                }
                throw new IOException("source disappeared");
            }

            @Override
            public int read() throws IOException {
                throw new IOException("unused");
            }
        };

        assertThrows(IOException.class, () -> spool.store(broken));

        try (Stream<Path> files = Files.list(root.resolve("tmp"))) {
            assertEquals(0, files.count());
        }
        try (Stream<Path> files = Files.list(root)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".eml")));
        }
    }

    @Test
    void validatesEveryPublicRawRef() {
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 1024);
        assertThrows(IllegalArgumentException.class, () -> spool.exists(null));
        assertThrows(IllegalArgumentException.class, () -> spool.delete("../" + UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> spool.size("not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> spool.open(""));
    }
}
