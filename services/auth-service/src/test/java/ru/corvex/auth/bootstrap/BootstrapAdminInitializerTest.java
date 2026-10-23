package ru.corvex.auth.bootstrap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.corvex.auth.domain.User;
import ru.corvex.auth.repository.UserRepository;
import ru.corvex.auth.security.PasswordPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BootstrapAdminInitializerTest {

    private UserRepository users;
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString()))
                .thenAnswer(invocation -> "enc(" + invocation.getArgument(0) + ")");
    }

    @Test
    void doesNothingWhenUsersAlreadyExist() {
        when(users.count()).thenReturn(3L);

        initializer("admin", "Password123", "admin@example.test").run(null);

        verify(users, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void createsConfiguredSuperAdminWithNormalizedIdentity() {
        when(users.count()).thenReturn(0L);

        initializer("  ROOT ", "Password123", " Root@Example.Test ").run(null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User admin = captor.getValue();
        assertEquals("root", admin.getUsername());
        assertEquals("root@example.test", admin.getEmail());
        assertEquals("Супер администратор", admin.getDisplayName());
        assertEquals("enc(Password123)", admin.getPasswordHash());
        assertEquals(User.Source.LOCAL, admin.getSource());
        assertEquals(User.Status.ACTIVE, admin.getStatus());
        assertTrue(admin.isMustChangePassword());
    }

    @Test
    void generatedPasswordSatisfiesPolicyAndBlankEmailUsesDefault() {
        when(users.count()).thenReturn(0L);

        initializer("admin", " ", " ").run(null);

        ArgumentCaptor<String> rawPassword = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(rawPassword.capture());
        assertEquals(20, rawPassword.getValue().length());
        assertTrue(rawPassword.getValue().startsWith("Cv9"));
        assertNull(PasswordPolicy.validate(rawPassword.getValue()));
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertEquals("admin@company.local", captor.getValue().getEmail());
    }

    @Test
    void nullPasswordIsAlsoGenerated() {
        when(users.count()).thenReturn(0L);

        initializer("admin", null, null).run(null);

        verify(users).save(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void weakConfiguredPasswordAbortsCreation() {
        when(users.count()).thenReturn(0L);

        initializer("admin", "short1", "admin@example.test").run(null);

        verify(users, never()).save(org.mockito.ArgumentMatchers.any());
        verify(passwordEncoder, never()).encode(anyString());
    }

    private BootstrapAdminInitializer initializer(String username, String password, String email) {
        return new BootstrapAdminInitializer(users, passwordEncoder, username, password, email);
    }
}
