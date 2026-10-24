package ru.corvex.mailstorage.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.api.dto.ExternalMailDtos;
import ru.corvex.mailstorage.api.dto.MailDtos;
import ru.corvex.mailstorage.api.dto.ProtocolDtos;
import ru.corvex.mailstorage.domain.ExternalMailSyncRun;
import ru.corvex.mailstorage.external.ExternalMailAccountService;
import ru.corvex.mailstorage.external.ExternalMailSyncService;
import ru.corvex.mailstorage.security.InternalTokenGuard;
import ru.corvex.mailstorage.service.MailApiService;
import ru.corvex.mailstorage.service.MailSendService;
import ru.corvex.mailstorage.service.ProtocolMailService;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageControllersTest {

    @TempDir
    Path tmp;

    private final UUID userId = UUID.randomUUID();
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        jwt = jwt(userId.toString(), List.of("MAIL_READ_OWN", "MAIL_EXTERNAL_ACCOUNTS", "ADMIN_MAIL_POLICY"));
    }

    @Test
    void mailControllerDelegatesWithJwtOwnerAndPermissions() throws Exception {
        MailApiService mail = mock(MailApiService.class);
        MailSendService sender = mock(MailSendService.class);
        MailController controller = new MailController(mail, sender);
        UUID folderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        MailDtos.FolderView folder = new MailDtos.FolderView(folderId, "INBOX", "INBOX", 2, 1);
        when(mail.listFolders(userId)).thenReturn(List.of(folder));
        MailDtos.SendMessageRequest request = new MailDtos.SendMessageRequest(
                List.of("a@x"), List.of(), List.of(), "S", "B", null, List.of(), true, null);
        MailDtos.SendMessageResult sent = new MailDtos.SendMessageResult(
                "<m@x>", null, List.of(), List.of("a@x"), "me@x", null);
        when(sender.send(userId, Set.of("MAIL_READ_OWN", "MAIL_EXTERNAL_ACCOUNTS", "ADMIN_MAIL_POLICY"), request))
                .thenReturn(sent);

        assertEquals(List.of(folder), controller.folders(jwt));
        controller.messages(jwt, folderId, 50L, 25);
        controller.message(jwt, messageId);
        controller.flags(jwt, messageId, new MailDtos.UpdateFlagsRequest(true, null, null, null));
        controller.move(jwt, messageId, new MailDtos.MoveMessageRequest(folderId));
        assertEquals(sent, controller.send(jwt, request));

        verify(mail).listMessages(userId, folderId, 50L, 25);
        verify(mail).getMessage(userId, messageId);
        verify(mail).moveMessage(userId, messageId, folderId);
    }

    @Test
    void mailControllerRejectsJwtWithoutUuidSubject() throws Exception {
        MailController controller = new MailController(mock(MailApiService.class), mock(MailSendService.class));
        Jwt bad = jwt("not-a-uuid", null);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.folders(bad)).getStatusCode());

        Jwt noPermissions = jwt(userId.toString(), null);
        MailSendService sender = mock(MailSendService.class);
        MailController local = new MailController(mock(MailApiService.class), sender);
        MailDtos.SendMessageRequest req = new MailDtos.SendMessageRequest(
                List.of(), List.of(), List.of(), null, null, null, null, null, null);
        when(sender.send(userId, Set.of(), req)).thenReturn(null);

        assertNull(local.send(noPermissions, req));
        verify(sender).send(userId, Set.of(), req);
    }

    @Test
    void externalControllerDelegatesEveryEndpoint() {
        ExternalMailAccountService accounts = mock(ExternalMailAccountService.class);
        ExternalMailSyncService sync = mock(ExternalMailSyncService.class);
        ExternalMailController controller = new ExternalMailController(accounts, sync);
        UUID accountId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Set<String> permissions = Set.of("MAIL_READ_OWN", "MAIL_EXTERNAL_ACCOUNTS", "ADMIN_MAIL_POLICY");
        ExternalMailDtos.AccountRequest request = null;
        ExternalMailDtos.EnabledRequest enabled = new ExternalMailDtos.EnabledRequest(true, true, false);

        controller.presets();
        controller.owners(jwt, "иван", 5);
        controller.owner(jwt, ownerId);
        controller.ownerFolders(jwt, ownerId);
        controller.list(jwt, ownerId);
        assertEquals(HttpStatus.CREATED, controller.create(jwt, request).getStatusCode());
        controller.test(jwt, request);
        controller.get(jwt, accountId);
        controller.update(jwt, accountId, request);
        assertEquals(HttpStatus.NO_CONTENT, controller.delete(jwt, accountId).getStatusCode());
        controller.enabled(jwt, accountId, enabled);
        controller.testStored(jwt, accountId);
        assertEquals(HttpStatus.ACCEPTED, controller.syncNow(jwt, accountId).getStatusCode());
        assertEquals(HttpStatus.ACCEPTED, controller.migrate(jwt, accountId).getStatusCode());
        assertEquals(HttpStatus.NO_CONTENT, controller.resetSync(jwt, accountId).getStatusCode());
        controller.runs(jwt, accountId);
        controller.audit(jwt, accountId);

        verify(accounts).presets();
        verify(accounts).searchOwners(userId, permissions, "иван", 5);
        verify(accounts).owner(userId, permissions, ownerId);
        verify(accounts).ownerFolders(userId, permissions, ownerId);
        verify(accounts).create(userId, permissions, request);
        verify(accounts).setEnabled(userId, permissions, accountId, enabled);
        verify(sync).requestSync(userId, permissions, accountId, ExternalMailSyncRun.Trigger.MANUAL);
        verify(sync).requestSync(userId, permissions, accountId, ExternalMailSyncRun.Trigger.MIGRATION);
        verify(accounts).resetSyncState(userId, permissions, accountId);
        verify(sync).listRuns(userId, permissions, accountId);
        verify(accounts).audit(userId, permissions, accountId);
    }

    @Test
    void internalExternalControllerRequiresTokenBeforeReturningSecretConfig() {
        ExternalMailAccountService accounts = mock(ExternalMailAccountService.class);
        InternalExternalMailController controller = new InternalExternalMailController(
                accounts, new InternalTokenGuard("internal"));
        UUID accountId = UUID.randomUUID();

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.transportConfig("wrong", accountId)).getStatusCode());
        verify(accounts, never()).transportConfig(accountId);

        assertNull(controller.transportConfig("internal", accountId));
        verify(accounts).transportConfig(accountId);
    }

    @Test
    void rawControllerStoresOpensDeletesAndMapsErrors() throws Exception {
        RawMessageSpool spool = new RawMessageSpool(tmp.resolve("spool"), 1024);
        RawMessageController controller = new RawMessageController(spool, new InternalTokenGuard("tok"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("raw-body".getBytes(StandardCharsets.UTF_8));

        var stored = controller.store("tok", request);

        assertEquals(HttpStatus.CREATED, stored.getStatusCode());
        assertEquals(8, stored.getBody().sizeBytes());
        var opened = controller.open("tok", stored.getBody().rawRef());
        assertEquals(8, opened.getHeaders().getContentLength());
        try (InputStream in = opened.getBody().getInputStream()) {
            assertEquals("raw-body", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(HttpStatus.NO_CONTENT, controller.delete("tok", stored.getBody().rawRef()).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> controller.open("tok", stored.getBody().rawRef())).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.open("tok", "../x")).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.delete("tok", "bad")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.delete("wrong", stored.getBody().rawRef())).getStatusCode());
    }

    @Test
    void rawControllerMapsOversizeToPayloadTooLarge() {
        RawMessageController controller = new RawMessageController(
                new RawMessageSpool(tmp.resolve("small"), 2), new InternalTokenGuard("tok"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("123".getBytes(StandardCharsets.UTF_8));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(ResponseStatusException.class,
                () -> controller.store("tok", request)).getStatusCode());
    }

    @Test
    void protocolControllerDelegatesAndBuildsRawResponses() throws Exception {
        ProtocolMailService service = mock(ProtocolMailService.class);
        InternalProtocolController controller = new InternalProtocolController(
                service, new InternalTokenGuard("tok"));
        UUID folderId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        ProtocolDtos.MessageMeta meta = new ProtocolDtos.MessageMeta(
                messageId, 7, 3, "<m@x>", "a@x", "S", Instant.now(), 4, false, false, false, false, false);
        when(service.openByUid(userId, folderId, 7L)).thenReturn(
                new ProtocolMailService.RawMessage(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), meta));
        when(service.openById(userId, messageId)).thenReturn(
                new ProtocolMailService.RawMessage(new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), meta));

        controller.folders("tok", userId);
        assertEquals(HttpStatus.CREATED, controller.createFolder("tok", userId,
                new ProtocolDtos.CreateFolderRequest("Folder", null)).getStatusCode());
        controller.status("tok", userId, folderId);
        controller.renameFolder("tok", userId, folderId,
                new ProtocolDtos.RenameFolderRequest("New", null, false));
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteFolder("tok", userId, folderId).getStatusCode());
        controller.messages("tok", userId, folderId, 3, 10);
        var rawUid = controller.rawByUid("tok", userId, folderId, 7);
        var rawId = controller.rawById("tok", userId, messageId);
        controller.flags("tok", userId, folderId, 7,
                new ProtocolDtos.FlagsUpdate(true, null, null, null, null));
        controller.expunge("tok", userId, folderId, null);
        controller.copy("tok", userId, folderId,
                new ProtocolDtos.CopyRequest(List.of(7L), folderId, false));
        assertEquals(HttpStatus.NO_CONTENT, controller.deleteMessage("tok", userId, messageId).getStatusCode());
        controller.quota("tok", userId);

        assertEquals(4, rawUid.getHeaders().getContentLength());
        assertEquals(messageId.toString(), rawUid.getHeaders().getFirst("X-Corvex-Message-Id"));
        assertEquals("7", rawUid.getHeaders().getFirst("X-Corvex-Message-Uid"));
        assertTrue(rawId.getBody() instanceof InputStreamResource);
        verify(service).listFolders(userId);
        verify(service).folderStatus(userId, folderId);
        verify(service).listMessages(userId, folderId, 3, 10);
        verify(service).hardDeleteMessage(userId, messageId);
        verify(service).quota(userId);
    }

    @Test
    void protocolAppendParsesDateAndFlagsAndRejectsBadDate() throws Exception {
        ProtocolMailService service = mock(ProtocolMailService.class);
        InternalProtocolController controller = new InternalProtocolController(
                service, new InternalTokenGuard("tok"));
        UUID folderId = UUID.randomUUID();
        Instant date = Instant.parse("2026-05-06T07:08:09Z");
        ProtocolDtos.AppendResponse response = new ProtocolDtos.AppendResponse(UUID.randomUUID(), 5, 6);
        when(service.append(eq(userId), eq(folderId), any(InputStream.class), eq(date),
                eq(Set.of("\\seen", "\\draft")))).thenReturn(response);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContent("eml".getBytes(StandardCharsets.UTF_8));

        var result = controller.append("tok", date.toString(), "\\Seen \\Draft", userId, folderId, request);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());

        MockHttpServletRequest badDate = new MockHttpServletRequest();
        badDate.setContent("eml".getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> controller.append("tok", "yesterday", null, userId, folderId, badDate)).getStatusCode());

        MockHttpServletRequest noDate = new MockHttpServletRequest();
        noDate.setContent("eml".getBytes(StandardCharsets.UTF_8));
        when(service.append(eq(userId), eq(folderId), any(InputStream.class), eq(null), eq(Set.of())))
                .thenReturn(response);
        assertEquals(response, controller.append("tok", " ", null, userId, folderId, noDate).getBody());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
                () -> controller.folders("wrong", userId)).getStatusCode());
    }

    private Jwt jwt(String subject, List<String> permissions) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject);
        if (permissions != null) builder.claim("permissions", permissions);
        return builder.build();
    }
}
