package ru.corvex.mailimap.server;

import org.apache.james.mailbox.model.MessageId;

import java.util.Objects;
import java.util.UUID;

/** James MessageId = UUID строки письма в mail-storage-service (он же POP3 UIDL). */
public record CorvexMessageId(UUID value) implements MessageId {

    public CorvexMessageId {
        Objects.requireNonNull(value, "value");
    }

    public static CorvexMessageId of(UUID value) {
        return new CorvexMessageId(value);
    }

    public static CorvexMessageId fromSerialized(String serialized) {
        return new CorvexMessageId(UUID.fromString(serialized));
    }

    @Override
    public String serialize() {
        return value.toString();
    }

    @Override
    public boolean isSerializable() {
        return true;
    }

    @Override
    public String toString() {
        return serialize();
    }

    public static class Factory implements MessageId.Factory {
        @Override
        public MessageId fromString(String serialized) {
            return CorvexMessageId.fromSerialized(serialized);
        }

        @Override
        public MessageId generate() {
            return new CorvexMessageId(UUID.randomUUID());
        }
    }
}
