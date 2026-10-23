package ru.corvex.auth.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.corvex.auth.domain.LoginAttempt;
import ru.corvex.auth.repository.LoginAttemptRepository;
import ru.corvex.auth.repository.RefreshSessionRepository;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSupportServicesTest {

    @Test
    void loginAttemptRecorderPersistsAllFields() {
        LoginAttemptRepository attempts = mock(LoginAttemptRepository.class);
        LoginAttemptRecorder recorder = new LoginAttemptRecorder(attempts);

        recorder.record("ivan", "192.0.2.10", false, LoginAttempt.REASON_BAD_CREDENTIALS);

        ArgumentCaptor<LoginAttempt> captor = ArgumentCaptor.forClass(LoginAttempt.class);
        verify(attempts).save(captor.capture());
        assertEquals("ivan", captor.getValue().getUsername());
        assertEquals("192.0.2.10", captor.getValue().getIpAddress());
        assertFalse(captor.getValue().isSuccess());
        assertEquals(LoginAttempt.REASON_BAD_CREDENTIALS, captor.getValue().getReason());
    }

    @Test
    void sessionRevokerDelegatesWithCurrentInstantAndNormalizesMissingKeepHash() {
        RefreshSessionRepository sessions = mock(RefreshSessionRepository.class);
        SessionRevoker revoker = new SessionRevoker(sessions);
        UUID userId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(sessions.revokeAllByUserId(eq(userId), any(Instant.class))).thenReturn(3);
        when(sessions.revokeAllExcept(eq(userId), eq(""), any(Instant.class))).thenReturn(2);
        when(sessions.revokeAllExcept(eq(userId), eq("keep"), any(Instant.class))).thenReturn(1);

        Instant before = Instant.now();
        assertEquals(3, revoker.revokeAll(userId));
        assertEquals(2, revoker.revokeAllExcept(userId, null));
        assertEquals(1, revoker.revokeAllExcept(userId, "keep"));

        ArgumentCaptor<Instant> instants = ArgumentCaptor.forClass(Instant.class);
        verify(sessions).revokeAllByUserId(eq(userId), instants.capture());
        assertTrue(!instants.getValue().isBefore(before));
    }
}
