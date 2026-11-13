package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JamesMessagePreviewMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JamesMessagePreviewMapper previews = new JamesMessagePreviewMapper();
    private final UUID owner = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();
    private final String json = "[{\"id\":\"m1\",\"subject\":\"Hello\",\"preview\":\"Body\","
            + "\"from\":[{\"name\":\"Ivan\",\"email\":\"i@example.test\"}],"
            + "\"receivedAt\":\"2026-01-01T00:00:00Z\",\"size\":15,\"keywords\":{\"$seen\":true},\"hasAttachment\":true}]";

    @Test void mapsMessageButNeverClaimsItWasScanned() throws Exception {
        var item = previews.map(owner, "account", folder, mapper.readTree(json)).get(0);
        assertEquals("Ivan <i@example.test>", item.sender());
        assertEquals("Hello", item.subject());
        assertTrue(item.seen());
        assertFalse(item.flagged());
        assertTrue(item.hasAttachments());
        assertEquals("UNVERIFIED", item.avStatus());
        assertEquals(8, item.id().version());
    }

    @Test void stableIdsAreScopedToOwnerAndAccountButNotFolder() throws Exception {
        var emails = mapper.readTree(json);
        var id = previews.map(owner, "account", folder, emails).get(0).id();
        assertEquals(id, previews.map(owner, "account", UUID.randomUUID(), emails).get(0).id());
        assertNotEquals(id, previews.map(UUID.randomUUID(), "account", folder, emails).get(0).id());
        assertNotEquals(id, previews.map(owner, "other", folder, emails).get(0).id());
    }

    @Test void missingNullableContentHasSafeEmptyValues() throws Exception {
        var data = mapper.readTree("[{\"id\":\"x\",\"subject\":null,\"preview\":null,\"from\":null,"
                + "\"receivedAt\":\"2026-01-01T00:00:00Z\",\"size\":0,\"keywords\":{},\"hasAttachment\":false}]");
        var item = previews.map(owner, "account", folder, data).get(0);
        assertEquals("", item.sender());
        assertEquals("", item.subject());
        assertEquals("", item.snippet());
        assertEquals("UNVERIFIED", item.avStatus());
    }

    @Test void missingOrNullSenderNameUsesEmailAndWrongTypeFails() throws Exception {
        String noName = json.replace("\"name\":\"Ivan\",", "");
        String nullName = json.replace("\"name\":\"Ivan\"", "\"name\":null");
        String wrongName = json.replace("\"name\":\"Ivan\"", "\"name\":123");
        assertEquals("i@example.test", previews.map(owner, "account", folder, mapper.readTree(noName)).get(0).sender());
        assertEquals("i@example.test", previews.map(owner, "account", folder, mapper.readTree(nullName)).get(0).sender());
        assertThrows(IOException.class, () -> previews.map(owner, "account", folder, mapper.readTree(wrongName)));
    }

    @Test void rejectsMalformedDateKeywordsAndMissingRequiredProperties() throws Exception {
        assertThrows(IOException.class, () -> previews.map(owner, "account", folder,
                mapper.readTree(json.replace("2026-01-01T00:00:00Z", "invalid"))));
        assertThrows(IOException.class, () -> previews.map(owner, "account", folder,
                mapper.readTree(json.replace("\"$seen\":true", "\"$seen\":false"))));
        assertThrows(IOException.class, () -> previews.map(owner, "account", folder,
                mapper.readTree(json.replace("\"size\":15", "\"size\":-1"))));
    }
}
