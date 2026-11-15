package ru.corvex.mailstorage.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Path;

/** Stage a copy; retain originals on commit until safe, durable reconciliation exists. */
public final class TransactionalMailMove {
    private static final Logger log = LoggerFactory.getLogger(TransactionalMailMove.class);
    private TransactionalMailMove() { }

    public static Path stage(MaildirStorage maildir, String original, String folder, boolean seen) throws IOException {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Перенос письма требует активной транзакции");
        }
        Path staged = maildir.stageMessageForMove(original, folder, seen);
        String stagedPath = staged.toString();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                // A committed MOVE never deletes the source: other rows or a concurrent
                // writer may still reference it. Unknown outcomes also keep both copies.
                String unused = status == STATUS_ROLLED_BACK ? stagedPath : null;
                if (unused == null) return;
                try { maildir.deleteMessage(unused); }
                catch (IOException e) { log.warn("Не удалось очистить копию письма после переноса: {}", e.getMessage()); }
            }
        });
        return staged;
    }
}
