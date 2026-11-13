package ru.corvex.mailstorage.james;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocked JMAP and database; not proof of live James or ownership. */
class JamesProvisioningDiscoveryTest {
    private final JamesProvisioningJournal journal = mock(JamesProvisioningJournal.class);
    private final JamesReadTokenIssuer issuer = mock(JamesReadTokenIssuer.class);
    private final JamesJmapReadClient client = mock(JamesJmapReadClient.class);
    private final JamesProvisioningDiscovery discovery = new JamesProvisioningDiscovery(journal, issuer, client);
    private final UUID actor = UUID.randomUUID(), owner = UUID.randomUUID();
    private final JamesProvisioningJournal.Intent intent = new JamesProvisioningJournal.Intent(UUID.randomUUID(), owner,
            actor, "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost");

    @Test void verifiedCreationDiscoversAccountAndRecordsDisabledBinding() throws Exception {
        when(journal.findCreated(intent.operationId(), actor)).thenReturn(Optional.of(intent));
        when(issuer.issue(any(), any())).thenReturn("token");
        when(client.session("token")).thenReturn(new ObjectMapper().readTree("""
            {"primaryAccounts":{"urn:ietf:params:jmap:mail":"account-123"}}
            """));
        discovery.complete(intent);
        verify(issuer).issue(argThat(p -> p.verifiedOwnerId().equals(owner)),
                argThat(m -> m.ownerId().equals(owner) && m.jamesLogin().equals(intent.login())));
        verify(journal).recordBinding(intent, "account-123");
    }

    @Test void absentOrAlteredIntentNeverMintsToken() throws Exception {
        when(journal.findCreated(intent.operationId(), actor)).thenReturn(Optional.empty(), Optional.of(intent));
        assertThrows(SecurityException.class, () -> discovery.complete(intent));
        var altered = new JamesProvisioningJournal.Intent(intent.operationId(), owner, actor,
                "cvx-" + UUID.randomUUID().toString().replace("-", "") + "@localhost");
        assertThrows(SecurityException.class, () -> discovery.complete(altered));
        verifyNoInteractions(issuer, client);
        verify(journal, never()).recordBinding(any(), any());
    }

    @Test void invalidSessionNeverWritesBinding() throws Exception {
        when(journal.findCreated(intent.operationId(), actor)).thenReturn(Optional.of(intent));
        when(issuer.issue(any(), any())).thenReturn("token");
        when(client.session("token")).thenReturn(new ObjectMapper().createObjectNode());
        assertThrows(IOException.class, () -> discovery.complete(intent));
        verify(journal, never()).recordBinding(any(), any());
    }
}
