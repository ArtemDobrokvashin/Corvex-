package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mocked orchestration: no HTTP, database or real account. */
class JamesProvisioningCoordinatorTest {
    private final JamesProvisioningJournal journal = mock(JamesProvisioningJournal.class);
    private final JamesWebAdminCreateClient admin = mock(JamesWebAdminCreateClient.class);
    private final JamesProvisioningCoordinator coordinator = new JamesProvisioningCoordinator(journal, admin);
    private final UUID owner = UUID.randomUUID(), actor = UUID.randomUUID();
    private final String password = "P".repeat(40);
    private final JamesProvisioningJournal.Intent intent = new JamesProvisioningJournal.Intent(
            UUID.randomUUID(), owner, actor, "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost");
    private final JamesProvisioningJournal.Submission submission = mock(JamesProvisioningJournal.Submission.class);

    @BeforeEach void setup() {
        when(journal.reserve(owner, actor)).thenReturn(intent);
        when(journal.beginCreate(intent)).thenReturn(submission);
    }

    @Test void intentIsCommittedBeforeSingleCreateAndConfirmedOnlyAfterSuccess() throws Exception {
        when(admin.create(intent.login(), password)).thenReturn(true);
        assertSame(intent, coordinator.create(owner, actor, password));
        InOrder order = inOrder(journal, admin);
        order.verify(journal).reserve(owner, actor);
        order.verify(journal).beginCreate(intent);
        order.verify(admin).create(intent.login(), password);
        order.verify(journal).confirmCreate(submission);
        verify(journal, never()).markUncertain(any());
        verify(journal, never()).recordBinding(any(), any());
    }

    @Test void unknownResponseIsBlockedNotRetried() throws Exception {
        when(admin.create(intent.login(), password)).thenReturn(false);
        assertThrows(IOException.class, () -> coordinator.create(owner, actor, password));
        verify(admin, times(1)).create(intent.login(), password);
        verify(journal).markUncertain(intent);
        verify(journal, never()).confirmCreate(any());
    }

    @Test void transportFailureRemainsUncertain() throws Exception {
        when(admin.create(intent.login(), password)).thenThrow(new IOException("secret " + password));
        IOException failure = assertThrows(IOException.class, () -> coordinator.create(owner, actor, password));
        assertFalse(failure.toString().contains(password));
        assertNull(failure.getCause());
        verify(admin, times(1)).create(intent.login(), password);
        verify(journal).markUncertain(intent);
        verify(journal, never()).confirmCreate(any());
    }

    @Test void failedUncertainWriteDoesNotRetryOrExposeUpstreamFailure() throws Exception {
        when(admin.create(intent.login(), password)).thenThrow(new IOException("secret " + password));
        doThrow(new IllegalStateException("database unavailable")).when(journal).markUncertain(intent);
        IOException failure = assertThrows(IOException.class, () -> coordinator.create(owner, actor, password));
        assertFalse(failure.toString().contains(password));
        verify(admin, times(1)).create(intent.login(), password);
        verify(journal, never()).confirmCreate(any());
    }

    @Test void failuresBeforeSubmissionNeverCallJames() {
        when(journal.beginCreate(intent)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> coordinator.create(owner, actor, password));
        verifyNoInteractions(admin);
    }

    @Test void databaseFailureAfterSuccessNeverRepeatsCreate() throws Exception {
        when(admin.create(intent.login(), password)).thenReturn(true);
        doThrow(new IllegalStateException("commit unavailable")).when(journal).confirmCreate(submission);
        assertThrows(IllegalStateException.class, () -> coordinator.create(owner, actor, password));
        verify(admin, times(1)).create(intent.login(), password);
        verify(journal, never()).markUncertain(any());
    }
}
