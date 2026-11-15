package ru.corvex.mailstorage.james;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Safe-to-display operation handle only; never includes credentials or login. */
final class JamesProvisioningUncertainException extends IOException {
    private final UUID operationId;

    JamesProvisioningUncertainException(UUID operationId) {
        super("James account creation outcome uncertain; do not retry");
        this.operationId = Objects.requireNonNull(operationId);
    }
    UUID operationId() { return operationId; }
}
