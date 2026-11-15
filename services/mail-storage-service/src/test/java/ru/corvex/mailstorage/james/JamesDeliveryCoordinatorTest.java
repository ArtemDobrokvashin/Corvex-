package ru.corvex.mailstorage.james;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock-only tests; no real SMTP, IMAP, James or PostgreSQL. */
class JamesDeliveryCoordinatorTest {
    private final JamesDeliveryJournal journal = mock(JamesDeliveryJournal.class);
    private final JamesDeliveryCoordinator.Transport transport = mock(JamesDeliveryCoordinator.Transport.class);
    private final JamesDeliveryCoordinator coordinator = new JamesDeliveryCoordinator(journal, transport);
    private final byte[] bytes = "wire bytes\r\n".getBytes(StandardCharsets.UTF_8);
    private final UUID owner = UUID.randomUUID();
    private final String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    private final JamesDeliveryCoordinator.Request request = new JamesDeliveryCoordinator.Request("raw", owner, "account", "folder", hash, bytes.length);
    private final JamesDeliveryJournal.Intent intent = new JamesDeliveryJournal.Intent(UUID.randomUUID(), "raw", owner, "account", "folder", hash, bytes.length);
    JamesDeliveryCoordinatorTest() throws Exception { }
    private ByteArrayInputStream input() { return new ByteArrayInputStream(bytes); }
    private void reserved() { when(journal.reserve(anyString(), any(), anyString(), anyString(), anyString(), anyLong()))
            .thenReturn(new JamesDeliveryJournal.Reservation(intent, "reserved")); }

    @Test void badBytesNeverReserveOrSend() {
        var altered = new ByteArrayInputStream("other bytes\n".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> coordinator.deliver(request, altered));
        verifyNoInteractions(journal, transport);
    }

    @Test void confirmedAndInterruptedAreNeverSubmittedAgain() throws Exception {
        when(journal.reserve(anyString(), any(), anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new JamesDeliveryJournal.Reservation(intent, "confirmed"), new JamesDeliveryJournal.Reservation(intent, "submitting"));
        assertEquals(JamesDeliveryCoordinator.Outcome.ALREADY_CONFIRMED, coordinator.deliver(request, input()));
        assertEquals(intent.operationId(), assertThrows(JamesDeliveryCoordinator.Uncertain.class, () -> coordinator.deliver(request, input())).operationId());
        verifyNoInteractions(transport);
        verify(journal, never()).begin(any());
    }

    @Test void successUsesExactlyVerifiedBytesAndConfirms() throws Exception {
        reserved();
        var ticket = mock(JamesDeliveryJournal.Submission.class);
        when(journal.begin(intent)).thenReturn(ticket);
        when(transport.appendOnce(eq("account"), eq("folder"), any(), eq((long) bytes.length))).thenAnswer(call -> {
            assertArrayEquals(bytes, ((java.io.InputStream) call.getArgument(2)).readAllBytes());
            return "remote";
        });
        assertEquals(JamesDeliveryCoordinator.Outcome.CONFIRMED, coordinator.deliver(request, input()));
        verify(journal).confirm(ticket, "remote");
        verify(transport, times(1)).appendOnce(anyString(), anyString(), any(), anyLong());
    }

    @Test void lostResponseOrConfirmationNeverRetries() throws Exception {
        reserved();
        var ticket = mock(JamesDeliveryJournal.Submission.class);
        when(journal.begin(intent)).thenReturn(ticket);
        when(transport.appendOnce(anyString(), anyString(), any(), anyLong())).thenThrow(new IOException("secret"));
        var ex = assertThrows(JamesDeliveryCoordinator.Uncertain.class, () -> coordinator.deliver(request, input()));
        assertFalse(ex.getMessage().contains("secret"));
        verify(journal).uncertain(ticket);
        verify(journal, never()).confirm(any(), anyString());
        verify(transport, times(1)).appendOnce(anyString(), anyString(), any(), anyLong());
    }
    @Test void failedConfirmationIsUncertainAndNotResent() throws Exception {
        reserved();
        var ticket = mock(JamesDeliveryJournal.Submission.class);
        when(journal.begin(intent)).thenReturn(ticket);
        when(transport.appendOnce(anyString(), anyString(), any(), anyLong())).thenAnswer(call -> {
            ((java.io.InputStream) call.getArgument(2)).readAllBytes(); return "remote";
        });
        doThrow(new IllegalStateException("database details")).when(journal).confirm(ticket, "remote");
        var ex = assertThrows(JamesDeliveryCoordinator.Uncertain.class, () -> coordinator.deliver(request, input()));
        assertEquals(intent.operationId(), ex.operationId());
        assertFalse(ex.getMessage().contains("database details"));
        verify(journal, never()).uncertain(any());
        verify(transport, times(1)).appendOnce(anyString(), anyString(), any(), anyLong());
    }

}
