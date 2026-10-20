package ru.corvex.mailimap.server;

import org.apache.james.mailbox.model.MailboxId;

import java.util.Objects;
import java.util.UUID;

/** James MailboxId = UUID строки папки в mail-storage-service. */
public record CorvexMailboxId(UUID value) implements MailboxId {

    public CorvexMailboxId {
        Objects.requireNonNull(value, "value");
    }

    public static CorvexMailboxId of(UUID value) {
        return new CorvexMailboxId(value);
    }

    public static CorvexMailboxId fromSerialized(String serialized) {
        return new CorvexMailboxId(UUID.fromString(serialized));
    }

    @Override
    public String serialize() {
        return value.toString();
    }

    @Override
    public String toString() {
        return serialize();
    }

    public static class Factory implements MailboxId.Factory {
        @Override
        public MailboxId fromString(String serialized) {
            return CorvexMailboxId.fromSerialized(serialized);
        }
    }
}
