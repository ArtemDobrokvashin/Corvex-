package ru.corvex.mailstorage.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RawMessageSpoolTest {

    @TempDir
    Path tmp;

    @Test
    void storesRawMessageWithHashAndSize() throws Exception {
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 1024);
        byte[] eml = "Subject: hi\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);

        RawMessageSpool.StoredRawMessage stored = spool.store(new ByteArrayInputStream(eml));

        assertEquals(eml.length, stored.sizeBytes());
        assertTrue(stored.sha256().matches("[0-9a-f]{64}"));
        try (var in = spool.open(stored.rawRef())) {
            assertEquals("Subject: hi\r\n\r\nbody", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void rejectsOversizeMessageWhileStreaming() {
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 4);
        assertThrows(RawMessageSpool.MessageTooLargeException.class,
                () -> spool.store(new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void rejectsPathTraversalRawRef() {
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 1024);
        assertThrows(IllegalArgumentException.class, () -> spool.open("../secret"));
    }
}
