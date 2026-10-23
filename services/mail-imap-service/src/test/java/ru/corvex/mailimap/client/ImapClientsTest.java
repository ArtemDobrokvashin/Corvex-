package ru.corvex.mailimap.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Внутренние JDK HTTP-клиенты проверяются против JDK HttpServer без внешних сервисов. */
class ImapClientsTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FOLDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MESSAGE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ConcurrentLinkedQueue<CapturedRequest> requests = new ConcurrentLinkedQueue<>();
    private final AtomicReference<Function<CapturedRequest, StubResponse>> responder =
            new AtomicReference<>(request -> StubResponse.json(404, "{}"));

    private HttpServer server;
    private String baseUrl;
    private ImapAuthClient auth;
    private ImapStorageClient storage;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            CapturedRequest request = new CapturedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    Map.of(
                            "token", String.valueOf(exchange.getRequestHeaders().getFirst("X-Corvex-Internal-Token")),
                            "contentType", String.valueOf(exchange.getRequestHeaders().getFirst("Content-Type")),
                            "internalDate", String.valueOf(exchange.getRequestHeaders().getFirst("X-Corvex-Internal-Date")),
                            "flags", String.valueOf(exchange.getRequestHeaders().getFirst("X-Corvex-Flags"))),
                    body);
            requests.add(request);
            StubResponse response = responder.get().apply(request);
            exchange.getResponseHeaders().set("Content-Type", response.contentType());
            if (response.status() == 204) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                exchange.sendResponseHeaders(response.status(), response.body().length);
                exchange.getResponseBody().write(response.body());
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        auth = new ImapAuthClient(baseUrl, "internal-secret", mapper);
        storage = new ImapStorageClient(baseUrl, "internal-secret", mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    // --------------------------------------------------------------------- auth

    @Test
    void authenticateLoadsOnlyActiveUserAndSendsCredentialsWithInternalToken() throws Exception {
        responder.set(request -> {
            if (request.uri().equals("/internal/verify-password")) {
                return StubResponse.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}");
            }
            if (request.uri().equals("/internal/users/" + USER_ID)) {
                return StubResponse.json(200, "{\"status\":\"active\",\"email\":\"ivanov@corp.local\"}");
            }
            return StubResponse.json(404, "{}");
        });

        Optional<ImapAuthClient.AuthenticatedUser> result = auth.authenticate("ivanov@corp.local", "secret");

        assertTrue(result.isPresent());
        assertEquals(USER_ID, result.orElseThrow().userId());
        assertEquals("ivanov@corp.local", result.orElseThrow().email());
        assertEquals("", result.orElseThrow().displayName());
        assertEquals(2, requests.size());
        CapturedRequest verify = requests.peek();
        assertEquals("POST", verify.method());
        assertEquals("internal-secret", verify.headers().get("token"));
        assertEquals("application/json", verify.headers().get("contentType"));
        JsonNode credentials = mapper.readTree(verify.body());
        assertEquals("ivanov@corp.local", credentials.get("username").asText());
        assertEquals("secret", credentials.get("password").asText());
    }

    @Test
    void authenticateRejectsInvalidPasswordNon200AndInactiveUser() throws Exception {
        responder.set(request -> StubResponse.json(200, "{\"valid\":false}"));
        assertTrue(auth.authenticate("ivanov@corp.local", "wrong").isEmpty());
        assertEquals(1, requests.size());

        requests.clear();
        responder.set(request -> StubResponse.json(503, "{}"));
        assertTrue(auth.authenticate("ivanov@corp.local", "wrong").isEmpty());

        requests.clear();
        responder.set(request -> request.uri().equals("/internal/verify-password")
                ? StubResponse.json(200, "{\"valid\":true,\"userId\":\"" + USER_ID + "\"}")
                : StubResponse.json(200, "{\"status\":\"BLOCKED\",\"email\":\"ivanov@corp.local\"}"));
        assertTrue(auth.authenticate("ivanov@corp.local", "secret").isEmpty());
        assertEquals(2, requests.size());
    }

    @Test
    void findActiveByUsernameEncodesQueryAndDefaultsMissingEmailToUsername() throws Exception {
        responder.set(request -> StubResponse.json(200,
                "{\"id\":\"" + USER_ID + "\",\"status\":\"ACTIVE\",\"displayName\":\"Иван Ivanov\"}"));

        Optional<ImapAuthClient.AuthenticatedUser> result =
                auth.findActiveByUsername("ivan+petrov@corp.local");

        assertTrue(result.isPresent());
        assertEquals("ivan+petrov@corp.local", result.orElseThrow().email());
        assertEquals("Иван Ivanov", result.orElseThrow().displayName());
        CapturedRequest request = requests.peek();
        assertEquals("/internal/users/by-email?email=ivan%2Bpetrov%40corp.local", request.uri());

        requests.clear();
        responder.set(ignored -> StubResponse.json(200,
                "{\"id\":\"" + USER_ID + "\",\"status\":\"DISABLED\",\"email\":\"ivanov@corp.local\"}"));
        assertTrue(auth.findActiveByUsername("ivanov@corp.local").isEmpty());

        responder.set(ignored -> StubResponse.json(404, "{}"));
        assertTrue(auth.findActiveByUsername("nobody@corp.local").isEmpty());
    }

    @Test
    void authClientFailsClosedWithoutTokenAndPropagatesBackendIoErrors() {
        ImapAuthClient noToken = new ImapAuthClient(baseUrl, " ", mapper);
        IOException missingToken = assertThrows(IOException.class,
                () -> noToken.authenticate("ivanov@corp.local", "secret"));
        assertInstanceOf(IllegalStateException.class, missingToken.getCause());
        assertThrows(IOException.class, () -> noToken.findActiveByUsername("ivanov@corp.local"));
        assertTrue(requests.isEmpty());

        ImapAuthClient unavailable = new ImapAuthClient("http://127.0.0.1:1", "secret", mapper);
        assertThrows(IOException.class, () -> unavailable.authenticate("ivanov@corp.local", "secret"));
        assertThrows(IOException.class, () -> unavailable.findActiveByUsername("ivanov@corp.local"));

        responder.set(ignored -> StubResponse.json(200, "not-json"));
        assertThrows(IOException.class, () -> auth.findActiveByUsername("ivanov@corp.local"));
    }

    // ------------------------------------------------------------------ storage

    @Test
    void storageFolderOperationsSerializeExpectedBodiesAndParseRecords() throws Exception {
        String folderJson = folderJson(FOLDER_ID, null, "INBOX");
        responder.set(request -> {
            if (request.method().equals("GET") && request.uri().endsWith("/folders")) {
                return StubResponse.json(200, "[" + folderJson + "]");
            }
            if (request.method().equals("POST") && request.uri().endsWith("/folders")) {
                return StubResponse.json(201, folderJson(TARGET_ID, FOLDER_ID, "Archive"));
            }
            if (request.method().equals("PATCH") && request.uri().endsWith("/folders/" + FOLDER_ID)) {
                return StubResponse.json(200, folderJson(FOLDER_ID, TARGET_ID, "Renamed"));
            }
            if (request.method().equals("GET") && request.uri().endsWith("/status")) {
                return StubResponse.json(200, folderJson);
            }
            return StubResponse.json(404, "{}");
        });

        List<ImapStorageClient.FolderInfo> folders = storage.listFolders(USER_ID);
        assertEquals(1, folders.size());
        assertEquals(new ImapStorageClient.FolderInfo(
                FOLDER_ID, null, "INBOX", "\\Inbox", 10, 77, 9, 3, 1), folders.get(0));

        ImapStorageClient.FolderInfo created = storage.createFolder(USER_ID, "Archive", FOLDER_ID);
        assertEquals(TARGET_ID, created.id());
        CapturedRequest create = requests.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
        JsonNode createBody = mapper.readTree(create.body());
        assertEquals("Archive", createBody.get("name").asText());
        assertEquals(FOLDER_ID.toString(), createBody.get("parentId").asText());
        assertEquals("internal-secret", create.headers().get("token"));
        assertEquals("application/json", create.headers().get("contentType"));

        storage.createFolder(USER_ID, "Root", null);
        CapturedRequest rootCreate = requests.stream().filter(r -> r.method().equals("POST"))
                .reduce((first, second) -> second).orElseThrow();
        assertFalse(mapper.readTree(rootCreate.body()).has("parentId"));

        ImapStorageClient.FolderInfo renamed = storage.renameFolder(USER_ID, FOLDER_ID, "Renamed");
        assertEquals("Renamed", renamed.name());
        CapturedRequest rename = requests.stream().filter(r -> r.method().equals("PATCH")).findFirst().orElseThrow();
        JsonNode renameBody = mapper.readTree(rename.body());
        assertFalse(renameBody.get("moveToRoot").asBoolean());
        assertFalse(renameBody.has("parentId"));

        storage.renameFolder(USER_ID, FOLDER_ID, "Moved", TARGET_ID, true);
        CapturedRequest moved = requests.stream().filter(r -> r.method().equals("PATCH"))
                .reduce((first, second) -> second).orElseThrow();
        JsonNode movedBody = mapper.readTree(moved.body());
        assertTrue(movedBody.get("moveToRoot").asBoolean());
        assertEquals(TARGET_ID.toString(), movedBody.get("parentId").asText());

        assertEquals(FOLDER_ID, storage.folderStatus(USER_ID, FOLDER_ID).id());
    }

    @Test
    void storageDeleteDistinguishesNoContentNotFoundAndOtherHttpErrors() throws Exception {
        responder.set(ignored -> StubResponse.json(204, ""));
        storage.deleteFolder(USER_ID, FOLDER_ID);
        assertEquals("DELETE", requests.peek().method());

        responder.set(ignored -> StubResponse.json(404, "{}"));
        assertThrows(ImapStorageClient.NotFoundException.class,
                () -> storage.deleteFolder(USER_ID, FOLDER_ID));

        responder.set(ignored -> StubResponse.json(500, "{}"));
        IOException error = assertThrows(IOException.class, () -> storage.deleteFolder(USER_ID, FOLDER_ID));
        assertFalse(error instanceof ImapStorageClient.NotFoundException);
        assertTrue(error.getMessage().contains("HTTP 500"));
    }

    @Test
    void storageMessageListRawFlagsExpungeAppendCopyAndQuotaRoundTrip() throws Exception {
        Instant internalDate = Instant.parse("2026-07-14T07:30:00Z");
        String meta = messageJson(internalDate);
        responder.set(request -> {
            String uri = request.uri();
            if (uri.contains("/messages?afterUid=5&limit=25")) {
                return StubResponse.json(200, "[" + meta + "]");
            }
            if (uri.endsWith("/messages/7/raw")) {
                return new StubResponse(200, "application/octet-stream",
                        "Subject: Hi\r\n\r\nBody".getBytes(StandardCharsets.UTF_8));
            }
            if (uri.endsWith("/messages/7/flags")) {
                return StubResponse.json(200, meta);
            }
            if (uri.endsWith("/expunge")) {
                return StubResponse.json(200, "{\"expungedUids\":[7,9]}");
            }
            if (request.method().equals("POST") && uri.endsWith("/messages")) {
                return StubResponse.json(201,
                        "{\"messageId\":\"" + MESSAGE_ID + "\",\"uid\":11,\"modseq\":12}");
            }
            if (uri.endsWith("/copy")) {
                return StubResponse.json(200, "{\"sourceUids\":[7,9],\"targetUids\":[20,21]}");
            }
            if (uri.endsWith("/quota")) {
                return StubResponse.json(200, "{\"usedBytes\":123,\"quotaBytes\":456,\"messageCount\":3}");
            }
            return StubResponse.json(404, "{}");
        });

        List<ImapStorageClient.MessageMeta> messages = storage.listMessages(USER_ID, FOLDER_ID, 5, 25);
        assertEquals(List.of(new ImapStorageClient.MessageMeta(
                MESSAGE_ID, 7, 8, "<m1@corp.local>", "petrov@corp.local", "Привет",
                internalDate, 42, true, false, true, false, false)), messages);

        try (InputStream raw = storage.openRawByUid(USER_ID, FOLDER_ID, 7)) {
            assertEquals("Subject: Hi\r\n\r\nBody", new String(raw.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertArrayEquals("Subject: Hi\r\n\r\nBody".getBytes(StandardCharsets.UTF_8),
                storage.readRawByUid(USER_ID, FOLDER_ID, 7));

        ImapStorageClient.MessageMeta updated = storage.updateFlags(USER_ID, FOLDER_ID, 7,
                new ImapStorageClient.FlagsUpdate(true, null, false, null, true));
        assertEquals(MESSAGE_ID, updated.id());
        CapturedRequest flagsRequest = requests.stream()
                .filter(r -> r.uri().endsWith("/flags")).findFirst().orElseThrow();
        JsonNode flags = mapper.readTree(flagsRequest.body());
        assertTrue(flags.get("seen").asBoolean());
        assertTrue(flags.get("answered").isNull());
        assertFalse(flags.get("flagged").asBoolean());
        assertTrue(flags.get("deleted").asBoolean());

        assertEquals(List.of(7L, 9L), storage.expunge(USER_ID, FOLDER_ID, List.of(7L, 9L)));
        assertEquals(List.of(7L, 9L), storage.expunge(USER_ID, FOLDER_ID, null));
        CapturedRequest emptyExpunge = requests.stream().filter(r -> r.uri().endsWith("/expunge"))
                .reduce((first, second) -> second).orElseThrow();
        assertEquals(0, mapper.readTree(emptyExpunge.body()).get("uids").size());

        byte[] eml = "Subject: Append\r\n\r\nText".getBytes(StandardCharsets.UTF_8);
        assertEquals(new ImapStorageClient.AppendResponse(MESSAGE_ID, 11, 12),
                storage.append(USER_ID, FOLDER_ID, eml, internalDate, "\\Seen \\Draft"));
        CapturedRequest append = requests.stream()
                .filter(r -> r.method().equals("POST") && r.uri().endsWith("/messages"))
                .findFirst().orElseThrow();
        assertArrayEquals(eml, append.body());
        assertEquals(internalDate.toString(), append.headers().get("internalDate"));
        assertEquals("\\Seen \\Draft", append.headers().get("flags"));
        assertEquals("application/octet-stream", append.headers().get("contentType"));

        storage.append(USER_ID, FOLDER_ID, eml, null, " ");
        CapturedRequest appendDefaults = requests.stream()
                .filter(r -> r.method().equals("POST") && r.uri().endsWith("/messages"))
                .reduce((first, second) -> second).orElseThrow();
        assertEquals("null", appendDefaults.headers().get("internalDate"));
        assertEquals("null", appendDefaults.headers().get("flags"));

        assertEquals(new ImapStorageClient.CopyResponse(List.of(7L, 9L), List.of(20L, 21L)),
                storage.copyMessages(USER_ID, FOLDER_ID, List.of(7L, 9L), TARGET_ID, true));
        CapturedRequest copy = requests.stream().filter(r -> r.uri().endsWith("/copy")).findFirst().orElseThrow();
        JsonNode copyBody = mapper.readTree(copy.body());
        assertEquals(TARGET_ID.toString(), copyBody.get("targetFolderId").asText());
        assertTrue(copyBody.get("move").asBoolean());
        assertEquals(9, copyBody.get("uids").get(1).asLong());

        assertEquals(new ImapStorageClient.QuotaView(123, 456, 3), storage.quota(USER_ID));
        assertNull(requests.peek().headers().get("unknown-header"));
    }

    @Test
    void storageJsonAndRawErrorsPreserveNotFoundVersusTemporaryFailure() {
        responder.set(ignored -> StubResponse.json(404, "{}"));
        assertThrows(ImapStorageClient.NotFoundException.class, () -> storage.listFolders(USER_ID));
        assertThrows(ImapStorageClient.NotFoundException.class,
                () -> storage.openRawByUid(USER_ID, FOLDER_ID, 7));

        responder.set(ignored -> StubResponse.json(503, "{}"));
        IOException jsonError = assertThrows(IOException.class, () -> storage.listFolders(USER_ID));
        assertFalse(jsonError instanceof ImapStorageClient.NotFoundException);
        IOException rawError = assertThrows(IOException.class,
                () -> storage.openRawByUid(USER_ID, FOLDER_ID, 7));
        assertTrue(rawError.getMessage().contains("HTTP 503"));

        responder.set(ignored -> StubResponse.json(200, "not-json"));
        assertThrows(IOException.class, () -> storage.listFolders(USER_ID));

        ImapStorageClient noToken = new ImapStorageClient(baseUrl, null, mapper);
        assertThrows(IOException.class, () -> noToken.quota(USER_ID));
        ImapStorageClient unavailable = new ImapStorageClient("http://127.0.0.1:1", "secret", mapper);
        assertThrows(IOException.class, () -> unavailable.listFolders(USER_ID));
    }

    private static String folderJson(UUID id, UUID parentId, String name) {
        return "{"
                + "\"id\":\"" + id + "\","
                + "\"parentId\":" + (parentId == null ? "null" : "\"" + parentId + "\"") + ","
                + "\"name\":\"" + name + "\","
                + "\"specialUse\":\"\\\\Inbox\","
                + "\"uidNext\":10,\"uidValidity\":77,\"highestModseq\":9,"
                + "\"messageCount\":3,\"unseenCount\":1}";
    }

    private static String messageJson(Instant internalDate) {
        return "{"
                + "\"id\":\"" + MESSAGE_ID + "\",\"uid\":7,\"modseq\":8,"
                + "\"messageIdHeader\":\"<m1@corp.local>\","
                + "\"sender\":\"petrov@corp.local\",\"subject\":\"Привет\","
                + "\"internalDate\":\"" + internalDate + "\",\"sizeBytes\":42,"
                + "\"seen\":true,\"answered\":false,\"flagged\":true,\"draft\":false,\"deleted\":false}";
    }

    private record CapturedRequest(String method, String uri, Map<String, String> headers, byte[] body) {
    }

    private record StubResponse(int status, String contentType, byte[] body) {
        private static StubResponse json(int status, String body) {
            return new StubResponse(status, "application/json", body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
