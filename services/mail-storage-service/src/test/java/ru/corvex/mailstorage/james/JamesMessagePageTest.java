package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JamesMessagePageTest {
    private static RSAPrivateKey key;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID owner = UUID.randomUUID();
    private final JamesMailboxBinding.Principal principal = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
    private final JamesMailboxBinding.ProvisionedMailbox mapping = new JamesMailboxBinding.ProvisionedMailbox(owner, true, "u@james.local", "account-1");
    private final JamesFolderMapper folders = new JamesFolderMapper();
    private final JamesJmapReadClient client = mock(JamesJmapReadClient.class);
    private JamesOwnMailboxReader reader;
    private UUID folderId;

    @BeforeAll static void generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        key = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
    }

    @BeforeEach void setup() throws Exception {
        reader = new JamesOwnMailboxReader(new JamesReadTokenIssuer(key, Clock.systemUTC(), mapper), client, mapper);
        var folderList = mapper.readTree("[{\"id\":\"inbox-native\",\"name\":\"Inbox\",\"role\":\"inbox\",\"totalEmails\":2,\"unreadEmails\":1}]");
        folderId = folders.map(owner, "account-1", folderList).get(0).id();
        when(client.session(anyString())).thenReturn(mapper.readTree("{\"primaryAccounts\":{\"urn:ietf:params:jmap:mail\":\"account-1\"}}"));
        var folderResponse = mapper.createObjectNode().put("accountId", "account-1");
        folderResponse.set("list", folderList);
        when(client.read(anyString(), eq("Mailbox/get"), any())).thenReturn(folderResponse);
    }

    private void query(String response) throws Exception {
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(response);
        json.put("queryState", "state-1").put("position", 0);
        when(client.read(anyString(), eq("Email/query"), any())).thenReturn(json);
    }
    private void emails(String response) throws Exception {
        when(client.read(anyString(), eq("Email/get"), any())).thenReturn(mapper.readTree(response));
    }

    @Test void readsOnlyResolvedFolderAndKeepsQueryOrder() throws Exception {
        query("{\"accountId\":\"account-1\",\"ids\":[\"b\",\"a\"]}");
        emails("{\"accountId\":\"account-1\",\"list\":[{\"id\":\"a\",\"mailboxIds\":{\"inbox-native\":true}},{\"id\":\"b\",\"mailboxIds\":{\"inbox-native\":true}}],\"notFound\":[]}");
        JsonNode result = reader.firstMessagePage(principal, mapping, folderId, 10, folders);
        assertEquals("b", result.get(0).path("id").asText());
        assertEquals("a", result.get(1).path("id").asText());
        verify(client).read(anyString(), eq("Email/query"), argThat(q -> q.path("accountId").asText().equals("account-1")
                && q.path("filter").path("inMailbox").asText().equals("inbox-native") && q.path("limit").asInt() == 10));
    }

    @Test void returnsEmptyWithoutEmailGet() throws Exception {
        query("{\"accountId\":\"account-1\",\"ids\":[]}");
        assertTrue(reader.firstMessagePage(principal, mapping, folderId, 10, folders).isEmpty());
        verify(client, never()).read(anyString(), eq("Email/get"), any());
    }

    @Test void refusesForeignFolderBeforeQuery() throws Exception {
        assertThrows(SecurityException.class, () -> reader.firstMessagePage(principal, mapping, UUID.randomUUID(), 10, folders));
        verify(client, never()).read(anyString(), eq("Email/query"), any());
    }

    @Test void refusesDuplicateOrForeignQueryIds() throws Exception {
        query("{\"accountId\":\"account-1\",\"ids\":[\"a\",\"a\"]}");
        assertThrows(IOException.class, () -> reader.firstMessagePage(principal, mapping, folderId, 10, folders));
        query("{\"accountId\":\"account-2\",\"ids\":[\"a\"]}");
        assertThrows(IOException.class, () -> reader.firstMessagePage(principal, mapping, folderId, 10, folders));
        verify(client, never()).read(anyString(), eq("Email/get"), any());
    }

    @Test void refusesMissingOrOtherFolderEmails() throws Exception {
        query("{\"accountId\":\"account-1\",\"ids\":[\"a\"]}");
        emails("{\"accountId\":\"account-1\",\"list\":[],\"notFound\":[\"a\"]}");
        assertThrows(IOException.class, () -> reader.firstMessagePage(principal, mapping, folderId, 10, folders));
        emails("{\"accountId\":\"account-1\",\"list\":[{\"id\":\"a\",\"mailboxIds\":{\"other\":true}}],\"notFound\":[]}");
        assertThrows(IOException.class, () -> reader.firstMessagePage(principal, mapping, folderId, 10, folders));
    }

    @Test void refusesContinuationWithoutAnyNetworkRequest() {
        assertThrows(UnsupportedOperationException.class,
                () -> reader.messagePage(principal, mapping, folderId, 10, folders, 10, null));
        verifyNoInteractions(client);
    }

    @Test void fullFirstPageExplicitlyReportsContinuationUnavailable() throws Exception {
        query("{\"accountId\":\"account-1\",\"ids\":[\"a\"]}");
        emails("{\"accountId\":\"account-1\",\"list\":[{\"id\":\"a\",\"mailboxIds\":{\"inbox-native\":true}}],\"notFound\":[]}");
        var page = reader.messagePage(principal, mapping, folderId, 1, folders, 0, null);
        assertEquals("state-1", page.queryState());
        assertTrue(page.continuationUnavailable());
    }

    @Test void rejectsPageOutsideBoundsWithoutAnyNetworkRequest() {
        assertThrows(IllegalArgumentException.class, () -> reader.firstMessagePage(principal, mapping, folderId, 0, folders));
        assertThrows(IllegalArgumentException.class, () -> reader.firstMessagePage(principal, mapping, folderId, 101, folders));
        verifyNoInteractions(client);
    }
}
