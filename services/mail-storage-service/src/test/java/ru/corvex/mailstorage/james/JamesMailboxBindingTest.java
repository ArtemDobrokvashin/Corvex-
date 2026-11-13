package ru.corvex.mailstorage.james;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JamesMailboxBindingTest {
    private final UUID owner = UUID.randomUUID();
    private final JamesMailboxBinding.Principal principal =
            new JamesMailboxBinding.Principal(owner, Set.of("MAIL_READ_OWN"));
    private final JamesMailboxBinding.ProvisionedMailbox mapping =
            new JamesMailboxBinding.ProvisionedMailbox(owner, true, "one@james.local", "account-1");

    @Test
    void permitsOnlyTheProvisionedOwnerAndAuthenticatedAccount() {
        var bound = JamesMailboxBinding.requireOwnRead(principal, mapping, "account-1");
        assertEquals("one@james.local", bound.jamesLogin());
        assertEquals("account-1", bound.accountId());
    }

    @Test
    void rejectsForeignOwnerAndForeignSession() {
        var foreign = new JamesMailboxBinding.ProvisionedMailbox(UUID.randomUUID(), true, "one@james.local", "account-1");
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(principal, foreign, "account-1"));
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(principal, mapping, "account-2"));
    }

    @Test
    void rejectsInactiveMissingPermissionAndMissingMapping() {
        var disabled = new JamesMailboxBinding.ProvisionedMailbox(owner, false, "one@james.local", "account-1");
        var noRead = new JamesMailboxBinding.Principal(owner, Set.of("MAIL_SEND_INTERNAL"));
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(principal, disabled, "account-1"));
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(noRead, mapping, "account-1"));
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(principal, null, "account-1"));
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(null, mapping, "account-1"));
    }

    @Test
    void rejectsIncompleteMappingAndNullSession() {
        for (var invalid : new JamesMailboxBinding.ProvisionedMailbox[] {
                new JamesMailboxBinding.ProvisionedMailbox(null, true, "one@james.local", "account-1"),
                new JamesMailboxBinding.ProvisionedMailbox(owner, true, " ", "account-1"),
                new JamesMailboxBinding.ProvisionedMailbox(owner, true, "one@james.local", null)}) {
            assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(principal, invalid, "account-1"));
        }
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(principal, mapping, null));
    }

    @Test
    void copiesPermissionSetToPreventMutationAfterVerification() {
        var permissions = new HashSet<String>();
        var p = new JamesMailboxBinding.Principal(owner, permissions);
        permissions.add("MAIL_READ_OWN");
        assertThrows(SecurityException.class, () -> JamesMailboxBinding.requireOwnRead(p, mapping, "account-1"));
    }
}
