package ru.corvex.mailstorage.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ru.corvex.mailstorage.client.AuthUserClient;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboundBounceListenerTest {

    @TempDir
    Path tmp;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AuthUserClient auth;
    private RawMessageSpool spool;
    private MailEventPublisher events;
    private OutboundBounceListener listener;

    @BeforeEach
    void setUp() {
        auth = mock(AuthUserClient.class);
        spool = new RawMessageSpool(tmp.resolve("spool"), 1_000_000);
        events = mock(MailEventPublisher.class);
        listener = new OutboundBounceListener(mapper, auth, spool, events);
    }

    @Test
    void usesCorvexOwnerFromSenderIdForExternalSendAsAndSanitizesSubject() throws Exception {
        UUID senderId = UUID.randomUUID();
        AuthUserClient.MailboxOwner owner = owner(senderId, "employee@company.local", "ACTIVE");
        when(auth.findById(senderId)).thenReturn(Optional.of(owner));
        MailPipelineEvents.OutboundBouncedEvent event = event(
                "External@Mail.Test", "victim@partner.test\r\nBcc: spy@x", senderId);

        listener.onBounced(mapper.writeValueAsString(event));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publishConfirmed(eq("mail.inbound.received"), payload.capture(), anyString());
        MailPipelineEvents.InboundReceivedEvent inbound =
                (MailPipelineEvents.InboundReceivedEvent) payload.getValue();
        assertEquals("MAILER-DAEMON@company.local", inbound.mailFrom());
        assertEquals(List.of("employee@company.local"), inbound.recipients());
        assertEquals("dsn", inbound.remoteAddress());
        String eml = readSpool(inbound.rawRef());
        String headers = eml.substring(0, eml.indexOf("\r\n\r\n"));
        assertTrue(headers.contains("To: employee@company.local"));
        assertFalse(headers.contains("\r\nBcc: spy@x"), "CRLF из recipient нельзя выпускать в Subject-заголовок");
        assertTrue(eml.contains("Original From: external@mail.test"));
        assertTrue(eml.contains("Original Message-ID: <original@x>"));
        assertTrue(eml.contains("Diagnostic: 550 rejected"));
        verify(auth, never()).findByEmail(anyString());
    }

    @Test
    void fallsBackToEnvelopeSenderWhenSenderIdLookupFails() throws Exception {
        UUID senderId = UUID.randomUUID();
        AuthUserClient.MailboxOwner owner = owner(senderId, "sender@company.local", "ACTIVE");
        when(auth.findById(senderId)).thenReturn(Optional.empty());
        when(auth.findByEmail("sender@company.local")).thenReturn(Optional.of(owner));
        MailPipelineEvents.OutboundBouncedEvent event = event(
                "SENDER@company.local", "rcpt@partner.test", senderId);

        listener.onBounced(mapper.writeValueAsString(event));

        verify(events).publishConfirmed(eq("mail.inbound.received"), any(), anyString());
    }

    @Test
    void skipsDsnWhenNoActiveOwnerCanBeResolved() throws Exception {
        UUID senderId = UUID.randomUUID();
        when(auth.findById(senderId)).thenReturn(Optional.of(owner(senderId, "a@company.local", "BLOCKED")));
        when(auth.findByEmail("a@company.local")).thenReturn(Optional.empty());
        MailPipelineEvents.OutboundBouncedEvent event = event("a@company.local", "r@x", senderId);

        listener.onBounced(mapper.writeValueAsString(event));

        verify(events, never()).publishConfirmed(anyString(), any(), anyString());
    }

    @Test
    void nullSenderIdAndAddressWithoutDomainUseLocalhostDaemon() throws Exception {
        UUID ownerId = UUID.randomUUID();
        when(auth.findByEmail("sender")).thenReturn(Optional.of(owner(ownerId, "sender", "ACTIVE")));
        MailPipelineEvents.OutboundBouncedEvent event = event("sender", "rcpt@x", null);

        listener.onBounced(mapper.writeValueAsString(event));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publishConfirmed(eq("mail.inbound.received"), payload.capture(), anyString());
        MailPipelineEvents.InboundReceivedEvent inbound =
                (MailPipelineEvents.InboundReceivedEvent) payload.getValue();
        assertEquals("MAILER-DAEMON@localhost", inbound.mailFrom());
        assertTrue(readSpool(inbound.rawRef()).contains("@localhost>"));
    }

    @Test
    void publishFailureDeletesGeneratedDsnAndRethrows() throws Exception {
        UUID senderId = UUID.randomUUID();
        when(auth.findById(senderId)).thenReturn(Optional.of(owner(senderId, "a@company.local", "ACTIVE")));
        AtomicReference<String> rawRef = new AtomicReference<>();
        doAnswer(inv -> {
            MailPipelineEvents.InboundReceivedEvent inbound = inv.getArgument(1);
            rawRef.set(inbound.rawRef());
            throw new IllegalStateException("broker down");
        }).when(events).publishConfirmed(eq("mail.inbound.received"), any(), anyString());
        MailPipelineEvents.OutboundBouncedEvent event = event("a@company.local", "r@x", senderId);

        assertThrows(IllegalStateException.class, () -> listener.onBounced(mapper.writeValueAsString(event)));

        assertFalse(spool.exists(rawRef.get()));
    }

    @Test
    void malformedBouncePayloadIsRejected() {
        assertThrows(JsonProcessingException.class, () -> listener.onBounced("[]"));
    }

    private String readSpool(String rawRef) throws Exception {
        try (InputStream in = spool.open(rawRef)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private AuthUserClient.MailboxOwner owner(UUID id, String email, String status) {
        return new AuthUserClient.MailboxOwner(id, email, "Owner", status, 0);
    }

    private MailPipelineEvents.OutboundBouncedEvent event(String from, String recipient, UUID senderId) {
        return new MailPipelineEvents.OutboundBouncedEvent(
                UUID.randomUUID().toString(), from, recipient, "550 rejected", "<original@x>",
                senderId, Instant.parse("2026-01-01T00:00:00Z"));
    }
}
