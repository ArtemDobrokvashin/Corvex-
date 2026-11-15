package ru.corvex.mailstorage.repository;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailFolderRepositoryTest {

    @Test
    void siblingLookupUsesSeparateQueriesForRootAndChild() {
        MailFolderRepository folders = mock(MailFolderRepository.class, CALLS_REAL_METHODS);
        UUID owner = UUID.randomUUID();
        UUID parent = UUID.randomUUID();
        when(folders.existsRootSibling(owner, "Root")).thenReturn(true);
        when(folders.existsChildSibling(owner, parent, "Child")).thenReturn(true);

        assertTrue(folders.existsSibling(owner, null, "Root"));
        assertTrue(folders.existsSibling(owner, parent, "Child"));
        verify(folders).existsRootSibling(owner, "Root");
        verify(folders).existsChildSibling(owner, parent, "Child");
        verify(folders, never()).existsChildSibling(owner, null, "Root");
    }
}
