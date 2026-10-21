package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.domain.MailFolder;
import ru.corvex.mailstorage.repository.ExternalMailAccountRepository;
import ru.corvex.mailstorage.repository.ExternalMailAuditEventRepository;
import ru.corvex.mailstorage.repository.ExternalMailFolderStateRepository;
import ru.corvex.mailstorage.repository.ExternalMailImportedMessageRepository;
import ru.corvex.mailstorage.repository.MailFolderRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalMailAccountServiceAdminTest {

    private ExternalMailAccountRepository accounts;
    private MailFolderRepository folders;
    private AuthUserClient auth;
    private ExternalMailAccountService service;

    @BeforeEach
    void setUp() {
        accounts = mock(ExternalMailAccountRepository.class);
        folders = mock(MailFolderRepository.class);
        auth = mock(AuthUserClient.class);
        service = new ExternalMailAccountService(
                accounts,
                mock(ExternalMailFolderStateRepository.class),
                mock(ExternalMailImportedMessageRepository.class),
                mock(ExternalMailAuditEventRepository.class),
                folders,
                auth,
                mock(ExternalMailSecretCipher.class),
                mock(ExternalMailEndpointPolicy.class),
                mock(ExternalMailProperties.class),
                mock(ExternalMailClient.class));
    }

    @Test
    void adminSearchesEmployeesByNameAndSeesExternalMailboxCounts() {
        UUID adminId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(auth.searchActiveUsers("иван", 10)).thenReturn(List.of(
                new AuthUserClient.UserSummary(
                        ownerId, "ivanov", "ivanov@company.local", "Иван Иванов",
                        "Бухгалтерия", "ACTIVE", 0)));
        when(accounts.countByOwnerId(ownerId)).thenReturn(2L);

        var result = service.searchOwners(
                adminId, Set.of(ExternalMailAccountService.ADMIN_PERMISSION), " иван ", 10);

        assertEquals(1, result.size());
        assertEquals(ownerId, result.get(0).id());
        assertEquals("Иван Иванов", result.get(0).displayName());
        assertEquals(2, result.get(0).externalAccountCount());
    }

    @Test
    void ordinaryEmployeeCannotSearchCompanyDirectoryThroughExternalMailApi() {
        UUID actorId = UUID.randomUUID();

        assertThrows(ResponseStatusException.class, () -> service.searchOwners(
                actorId, Set.of(ExternalMailAccountService.PERMISSION), "", 25));

        verify(auth, never()).searchActiveUsers(anyString(), anyInt());
    }

    @Test
    void adminGetsSelectedEmployeesFoldersWithNestedPaths() {
        UUID adminId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        MailFolder parent = folder(ownerId, "Проекты", null);
        MailFolder child = folder(ownerId, "2026", parent);
        when(auth.findUserById(ownerId)).thenReturn(Optional.of(
                new AuthUserClient.UserSummary(
                        ownerId, "ivanov", "ivanov@company.local", "Иван Иванов",
                        null, "ACTIVE", 0)));
        when(folders.findAllByOwnerIdOrderBySpecialUseAscNameAsc(ownerId)).thenReturn(List.of(parent, child));

        var result = service.ownerFolders(
                adminId, Set.of(ExternalMailAccountService.ADMIN_PERMISSION), ownerId);

        assertEquals(2, result.size());
        assertEquals("Проекты / 2026", result.get(1).path());
        assertEquals(parent.getId(), result.get(1).parentId());
    }

    @Test
    void employeeCannotReadAnotherEmployeesTargetFolders() {
        UUID actorId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID();

        assertThrows(ResponseStatusException.class, () -> service.ownerFolders(
                actorId, Set.of(ExternalMailAccountService.PERMISSION), otherOwnerId));

        verify(folders, never()).findAllByOwnerIdOrderBySpecialUseAscNameAsc(otherOwnerId);
    }

    private MailFolder folder(UUID ownerId, String name, MailFolder parent) {
        MailFolder folder = new MailFolder();
        folder.setId(UUID.randomUUID());
        folder.setOwnerId(ownerId);
        folder.setParent(parent);
        folder.setName(name);
        folder.setUidNext(1);
        folder.setUidValidity(1);
        folder.setHighestModseq(1);
        return folder;
    }
}
