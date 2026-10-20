package ru.corvex.mailimap.server;

import org.apache.james.imap.api.process.ImapProcessor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * SmartLifecycle-интеграция Джеймса: Spring должен реально вызывать start/stop адаптера,
 * а выключенный флагом IMAP не должен пытаться открыть порты или ронять контекст.
 */
class JamesImapServerAdapterTest {

    private JamesImapServerAdapter adapter(boolean enabled, boolean plainEnabled, boolean tlsEnabled) {
        return new JamesImapServerAdapter(
                mock(ImapProcessor.class),
                NoopMetricFactory.INSTANCE,
                enabled,
                "127.0.0.1",
                plainEnabled,
                0,
                false,
                tlsEnabled,
                0,
                false,
                true,
                "",
                "",
                "PKCS12",
                "",
                "",
                "localhost",
                "",
                1024,
                65536,
                65536,
                1800,
                120,
                0,
                0);
    }

    @Test
    void disabledAdapterStartsAndStopsAsNoopLifecycleBean() {
        JamesImapServerAdapter adapter = adapter(false, true, true);

        assertDoesNotThrow(adapter::start);
        assertFalse(adapter.isRunning(), "CORVEX_IMAP_ENABLED=false не должен открывать слушатели");
        assertDoesNotThrow(() -> adapter.notifyNewMail("ivanov@corp.local", "INBOX"));
        assertDoesNotThrow(adapter::stop);
        assertFalse(adapter.isRunning());
    }

    @Test
    void enabledAdapterWithNoListenersFailsFastAndStaysStopped() {
        JamesImapServerAdapter adapter = adapter(true, false, false);

        assertThrows(IllegalStateException.class, adapter::start,
                "включённый IMAP без единого слушателя — ошибка конфигурации, а не тихий мёртвый порт");
        assertFalse(adapter.isRunning());
    }
}
