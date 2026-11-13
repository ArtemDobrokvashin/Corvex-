package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JamesFolderMapperTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JamesFolderMapper folders = new JamesFolderMapper();
    private final UUID owner = UUID.randomUUID();

    @Test void mapsNativeFolderAndRoleWithoutExposingNativeId() throws Exception {
        var list = mapper.readTree("[{\"id\":\"opaque-1\",\"name\":\"Inbox\",\"role\":\"inbox\",\"totalEmails\":9,\"unreadEmails\":2}]");
        var folder = folders.map(owner, "account-1", list).get(0);
        assertEquals("Inbox", folder.name());
        assertEquals("INBOX", folder.specialUse());
        assertEquals(9, folder.total());
        assertEquals(2, folder.unread());
        assertEquals(8, folder.id().version());
    }

    @Test void idsAreStableAcrossRenameAndBoundToOwnerAndAccount() throws Exception {
        var original = mapper.readTree("[{\"id\":\"same\",\"name\":\"A\",\"role\":null,\"totalEmails\":0,\"unreadEmails\":0}]");
        var renamed = mapper.readTree("[{\"id\":\"same\",\"name\":\"B\",\"role\":null,\"totalEmails\":0,\"unreadEmails\":0}]");
        UUID id = folders.map(owner, "account", original).get(0).id();
        assertEquals(id, folders.map(owner, "account", renamed).get(0).id());
        assertNotEquals(id, folders.map(UUID.randomUUID(), "account", original).get(0).id());
        assertNotEquals(id, folders.map(owner, "other-account", original).get(0).id());
    }

    @Test void resolvesOnlyWithinCurrentOwnersValidatedList() throws Exception {
        var list = mapper.readTree("[{\"id\":\"private-native-id\",\"name\":\"Inbox\",\"totalEmails\":0,\"unreadEmails\":0}]");
        UUID id = folders.map(owner, "account", list).get(0).id();
        assertEquals("private-native-id", folders.resolveNativeId(owner, "account", list, id).orElseThrow());
        assertTrue(folders.resolveNativeId(UUID.randomUUID(), "account", list, id).isEmpty());
        assertTrue(folders.resolveNativeId(owner, "other-account", list, id).isEmpty());
        assertTrue(folders.resolveNativeId(owner, "account", mapper.readTree("[]"), id).isEmpty());
    }

    @Test void rejectsDuplicatesAndInvalidCounts() throws Exception {
        var duplicate = mapper.readTree("[{\"id\":\"x\",\"name\":\"A\",\"totalEmails\":1,\"unreadEmails\":0},{\"id\":\"x\",\"name\":\"B\",\"totalEmails\":1,\"unreadEmails\":0}]");
        var invalid = mapper.readTree("[{\"id\":\"x\",\"name\":\"A\",\"totalEmails\":1,\"unreadEmails\":2}]");
        assertThrows(IOException.class, () -> folders.map(owner, "account", duplicate));
        assertThrows(IOException.class, () -> folders.map(owner, "account", invalid));
        assertThrows(IOException.class, () -> folders.map(owner, "account", mapper.readTree("{}")));
    }

    @Test void handlesCustomAndSpamRoles() throws Exception {
        var list = mapper.readTree("[{\"id\":\"a\",\"name\":\"Junk\",\"role\":\"junk\",\"totalEmails\":0,\"unreadEmails\":0},{\"id\":\"b\",\"name\":\"Custom\",\"role\":null,\"totalEmails\":0,\"unreadEmails\":0}]");
        var result = folders.map(owner, "account", list);
        assertEquals("SPAM", result.get(0).specialUse());
        assertNull(result.get(1).specialUse());
    }
}
