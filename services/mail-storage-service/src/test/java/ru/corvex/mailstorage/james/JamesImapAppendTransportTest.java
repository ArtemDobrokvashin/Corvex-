package ru.corvex.mailstorage.james;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.eclipse.angus.mail.iap.Literal;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mock-only: does not prove TLS, byte preservation or behavior of a live server. */
class JamesImapAppendTransportTest {
    private final IMAPFolder folder = mock(IMAPFolder.class);
    private final IMAPProtocol protocol = mock(IMAPProtocol.class);
    private final byte[] message = "Subject: one\r\n\r\nbody\r\n".getBytes(StandardCharsets.US_ASCII);
    private JamesImapAppendTransport transport() throws Exception {
        when(folder.getFullName()).thenReturn("INBOX");
        when(folder.doCommand(any())).thenAnswer(invocation -> ((IMAPFolder.ProtocolCommand) invocation.getArgument(0)).doCommand(protocol));
        when(protocol.hasCapability("UIDPLUS")).thenReturn(true);
        return new JamesImapAppendTransport("account", "INBOX", folder);
    }
    @Test void usesExactLiteralOnceAndReturnsAppendUid() throws Exception {
        var t = transport();
        when(protocol.appenduid(eq("INBOX"), isNull(), isNull(), any())).thenAnswer(invocation -> {
            Literal literal = invocation.getArgument(3);
            assertEquals(message.length, literal.size());
            var out = new ByteArrayOutputStream(); literal.writeTo(out);
            assertArrayEquals(message, out.toByteArray());
            assertThrows(IOException.class, () -> literal.writeTo(out));
            return new AppendUID(9, 42);
        });
        assertEquals("9:42", t.appendOnce("account", "INBOX", new ByteArrayInputStream(message), message.length));
        assertThrows(IOException.class, () -> t.appendOnce("account", "INBOX", new ByteArrayInputStream(message), message.length));
        verify(protocol, times(1)).appenduid(anyString(), isNull(), isNull(), any());
    }
    @Test void wrongTargetNeverCallsProtocol() throws Exception {
        var t = transport();
        assertThrows(IllegalArgumentException.class, () -> t.appendOnce("other", "INBOX", new ByteArrayInputStream(message), message.length));
        verify(folder, never()).doCommand(any());
    }
    @Test void missingOrInvalidAppendUidFailsClosed() throws Exception {
        var t = transport();
        when(protocol.appenduid(anyString(), isNull(), isNull(), any())).thenReturn(null);
        assertThrows(IOException.class, () -> t.appendOnce("account", "INBOX", new ByteArrayInputStream(message), message.length));
        assertThrows(IOException.class, () -> t.appendOnce("account", "INBOX", new ByteArrayInputStream(message), message.length));
    }
    @Test void missingUidPlusNeverSendsAppend() throws Exception {
        var t = transport(); when(protocol.hasCapability("UIDPLUS")).thenReturn(false);
        // Real IMAPFolder converts ProtocolException to MessagingException; the
        // mock forwards it directly, so assert failure rather than exception type.
        assertThrows(Exception.class, () -> t.appendOnce("account", "INBOX", new ByteArrayInputStream(message), message.length));
        verify(protocol, never()).appenduid(anyString(), isNull(), isNull(), any());
    }
}
