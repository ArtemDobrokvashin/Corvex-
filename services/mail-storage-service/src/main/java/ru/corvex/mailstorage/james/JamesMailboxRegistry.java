package ru.corvex.mailstorage.james;

import java.util.Optional;
import java.util.UUID;

/** Lookup only. Provisioning, validation, access control and audit are separate. */
interface JamesMailboxRegistry {
    Optional<JamesMailboxBinding.ProvisionedMailbox> find(UUID ownerId);
}
