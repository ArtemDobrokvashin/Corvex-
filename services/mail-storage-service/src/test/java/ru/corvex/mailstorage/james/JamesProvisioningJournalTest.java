package ru.corvex.mailstorage.james;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Unit tests only: mocked JDBC is not a PostgreSQL transaction test. */
class JamesProvisioningJournalTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private JamesProvisioningJournal journal;

    @BeforeEach void setup() {
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        journal = new JamesProvisioningJournal(jdbc, transactions);
    }

    @Test void generatedIdentityAndBindingAreDisabled() {
        var intent = journal.reserve(UUID.randomUUID(), UUID.randomUUID());
        assertTrue(intent.login().matches("cvx-[0-9a-f]{32}@localhost"));
        var submission = journal.beginCreate(intent);
        journal.confirmCreate(submission);
        journal.recordBinding(intent, "account-one");
        verify(jdbc).update(contains("VALUES (?, ?, ?, false)"), eq(intent.ownerId()), eq(intent.login()), eq("account-one"));
        verify(transactions, times(4)).commit(any());
    }

    @Test void aDifferentJournalCannotConfirmSubmission() {
        var intent = journal.reserve(UUID.randomUUID(), UUID.randomUUID());
        var submission = journal.beginCreate(intent);
        var restarted = new JamesProvisioningJournal(jdbc, transactions);
        assertThrows(SecurityException.class, () -> restarted.confirmCreate(submission));
        assertThrows(SecurityException.class, () -> restarted.confirmCreate(null));
    }

    @Test void missingExpectedPhaseFailsAndRollsBack() {
        var intent = new JamesProvisioningJournal.Intent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost");
        when(jdbc.update(startsWith("UPDATE corvex_james_provisioning"), any(Object[].class))).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> journal.beginCreate(intent));
        verify(transactions).rollback(any());
        verify(jdbc, never()).update(startsWith("INSERT INTO corvex_james_provisioning_event"), any(Object[].class));
    }

    @Test void rejectsInvalidAccountBeforeTransaction() {
        var intent = new JamesProvisioningJournal.Intent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost");
        assertThrows(IllegalArgumentException.class, () -> journal.recordBinding(intent, " "));
        verifyNoInteractions(jdbc, transactions);
    }
}
