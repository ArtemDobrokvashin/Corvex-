package ru.corvex.mailpop3.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolStorageClientTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID INBOX_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID MESSAGE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void listsFoldersAndResolvesInboxBySpecialUse() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer(request ->
                FakeHttpServer.Response.json("[" + folderJson(SENT_ID, "Отправленные", "SENT") + ","
                        + folderJson(INBOX_ID, "Входящие", "INBOX") + "]"))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl() + "/", "secret", mapper);

            List<ProtocolStorageClient.FolderInfo> folders = client.listFolders(USER_ID);
            ProtocolStorageClient.FolderInfo inbox = client.findInbox(USER_ID).orElseThrow();

            assertEquals(2, folders.size());
            assertEquals(INBOX_ID, inbox.id());
            assertEquals("Входящие", inbox.name());
            assertEquals(12, inbox.messageCount());
            assertEquals(2, server.requests().size());
            assertTrue(server.requests().stream().allMatch(request ->
                    request.path().equals("/internal/protocol/users/" + USER_ID + "/folders")
                            && "secret".equals(request.header("X-Corvex-Internal-Token"))));
            server.rethrowFailure();
        }
    }

    @Test
    void findInboxReturnsEmptyWhenNoInboxFolderExists() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer(request ->
                FakeHttpServer.Response.json("[" + folderJson(SENT_ID, "Архив", "ARCHIVE") + "]"))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            assertTrue(client.findInbox(USER_ID).isEmpty());
            server.rethrowFailure();
        }
    }

    @Test
    void listsMessagesWithPaginationQueryAndParsesMetadata() throws Exception {
        String json = "[{"
                + "\"id\":\"" + MESSAGE_ID + "\",\"uid\":42,\"modseq\":77,"
                + "\"messageIdHeader\":\"<m42@corp.local>\",\"sender\":\"ivanov@corp.local\","
                + "\"subject\":\"Отчёт\",\"internalDate\":\"2026-10-23T07:00:00Z\",\"sizeBytes\":1234,"
                + "\"seen\":true,\"answered\":false,\"flagged\":true,\"draft\":false,\"deleted\":false}]";
        try (FakeHttpServer server = new FakeHttpServer(request -> FakeHttpServer.Response.json(json))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            List<ProtocolStorageClient.MessageMeta> messages =
                    client.listMessages(USER_ID, INBOX_ID, 41, 25);

            assertEquals(1, messages.size());
            ProtocolStorageClient.MessageMeta message = messages.get(0);
            assertEquals(MESSAGE_ID, message.id());
            assertEquals(42, message.uid());
            assertEquals(Instant.parse("2026-10-23T07:00:00Z"), message.internalDate());
            assertEquals(1234, message.sizeBytes());
            assertTrue(message.seen());
            assertTrue(message.flagged());
            assertEquals(
                    "/internal/protocol/users/" + USER_ID + "/folders/" + INBOX_ID
                            + "/messages?afterUid=41&limit=25",
                    server.requests().get(0).path());
            server.rethrowFailure();
        }
    }

    @Test
    void httpErrorsAndMalformedJsonBecomeIOException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer(request -> FakeHttpServer.Response.status(503))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            IOException error = assertThrows(IOException.class, () -> client.listFolders(USER_ID));
            assertTrue(error.getMessage().contains("listFolders"));
            assertTrue(error.getMessage().contains("503"));
        }
        try (FakeHttpServer server = new FakeHttpServer(request -> FakeHttpServer.Response.json("{broken"))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            assertThrows(IOException.class, () -> client.listMessages(USER_ID, INBOX_ID, 0, 10));
        }
    }

    @Test
    void opensRawMessageAndRejectsStorageErrors() throws Exception {
        byte[] eml = "Subject: POP3\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);
        try (FakeHttpServer server = new FakeHttpServer(request ->
                request.path().endsWith("/raw")
                        ? FakeHttpServer.Response.bytes(eml)
                        : FakeHttpServer.Response.status(404))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            try (InputStream input = client.openRawById(USER_ID, MESSAGE_ID)) {
                assertArrayEquals(eml, input.readAllBytes());
            }
            assertEquals(
                    "/internal/protocol/users/" + USER_ID + "/messages/" + MESSAGE_ID + "/raw",
                    server.requests().get(0).path());
            server.rethrowFailure();
        }
        try (FakeHttpServer server = new FakeHttpServer(request -> FakeHttpServer.Response.status(503))) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            IOException error = assertThrows(IOException.class,
                    () -> client.openRawById(USER_ID, MESSAGE_ID));
            assertTrue(error.getMessage().contains("openRawById"));
            assertTrue(error.getMessage().contains("503"));
        }
    }

    @Test
    void deleteAcceptsNoContentAndNotFoundButRejectsOtherStatuses() throws Exception {
        UUID noContentId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID notFoundId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        UUID failedId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        try (FakeHttpServer server = new FakeHttpServer(request -> {
            if (request.path().endsWith(noContentId.toString())) {
                return FakeHttpServer.Response.status(204);
            }
            if (request.path().endsWith(notFoundId.toString())) {
                return FakeHttpServer.Response.status(404);
            }
            return FakeHttpServer.Response.status(500);
        })) {
            ProtocolStorageClient client = new ProtocolStorageClient(server.baseUrl(), "secret", mapper);

            client.deleteMessage(USER_ID, noContentId);
            client.deleteMessage(USER_ID, notFoundId);
            IOException error = assertThrows(IOException.class,
                    () -> client.deleteMessage(USER_ID, failedId));

            assertTrue(error.getMessage().contains("deleteMessage"));
            assertTrue(server.requests().stream().allMatch(request -> "DELETE".equals(request.method())));
            server.rethrowFailure();
        }
    }

    @Test
    void missingTokenFailsBeforeHttpRequest() {
        ProtocolStorageClient blank = new ProtocolStorageClient("http://127.0.0.1:1", " ", mapper);
        ProtocolStorageClient missing = new ProtocolStorageClient("http://127.0.0.1:1", null, mapper);

        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен",
                assertThrows(IOException.class, () -> blank.listFolders(USER_ID)).getMessage());
        assertEquals("CORVEX_INTERNAL_API_TOKEN не настроен",
                assertThrows(IOException.class, () -> missing.deleteMessage(USER_ID, MESSAGE_ID)).getMessage());
    }

    private static String folderJson(UUID id, String name, String specialUse) {
        return "{"
                + "\"id\":\"" + id + "\",\"parentId\":null,\"name\":\"" + name + "\","
                + "\"specialUse\":\"" + specialUse + "\",\"uidNext\":43,\"uidValidity\":99,"
                + "\"highestModseq\":77,\"messageCount\":12,\"unseenCount\":3}";
    }
}
