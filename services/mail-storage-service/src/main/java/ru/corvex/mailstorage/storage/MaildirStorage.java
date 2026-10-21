package ru.corvex.mailstorage.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Файловый слой хранилища почты. Два разных механизма — сознательно:
 * <ol>
 *   <li><b>Maildir</b> для целых .eml: запись в {@code tmp/} → fsync файла → rename в {@code new/}.
 *       Сбой в середине записи никогда не даёт «битое» письмо в папке; формат совместим со
 *       стандартными инструментами восстановления и миграции (doveadm, imapsync и т.д.).</li>
 *   <li><b>Контентно-адресуемое хранилище вложений</b> по SHA-256: один и тот же файл,
 *       разосланный 200 сотрудникам, физически хранится один раз (путь aa/bb/&lt;hash&gt;).</li>
 * </ol>
 * На Windows-хосте том монтируется в Linux-контейнер — имена файлов генерируются без «:»
 * (классический разделитель Maildir запрещён в NTFS), вместо него используется «;».
 */
@Component
public class MaildirStorage {

    private final Path mailRoot;
    private final Path attachmentRoot;

    public MaildirStorage(@Value("${corvex.mail.root:/data/mail}") Path mailRoot,
                          @Value("${corvex.mail.attachments:/data/attachments}") Path attachmentRoot) {
        this.mailRoot = mailRoot;
        this.attachmentRoot = attachmentRoot;
    }

    /** Сохраняет сырой .eml в Maildir пользователя/папки; возвращает относительный путь для БД. */
    public Path storeMessage(String mailbox, String folder, InputStream eml) throws IOException {
        return storeMessage(mailbox, folder, eml, false);
    }

    /** Вариант для APPEND/миграции: seen-письмо попадает в cur/, новое — в new/. */
    public Path storeMessage(String mailbox, String folder, InputStream eml, boolean seen) throws IOException {
        Path dir = mailRoot.resolve(sanitize(mailbox)).resolve(sanitize(folder));
        Files.createDirectories(dir.resolve("tmp"));
        Files.createDirectories(dir.resolve("new"));
        Files.createDirectories(dir.resolve("cur"));

        String name = System.currentTimeMillis() + "." + UUID.randomUUID() + ".eml";
        Path tmp = dir.resolve("tmp").resolve(name);
        try {
            writeAndSync(eml, tmp);
            Path target = dir.resolve(seen ? "cur" : "new").resolve(name);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target); // rename в пределах каталога всё равно одна операция ФС
            }
            return mailRoot.relativize(target);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    public record StoredAttachment(String sha256, long sizeBytes) {
    }

    /** Сохраняет вложение; SHA-256 — ключ дедупликации и FK в mail_db.attachments. */
    public StoredAttachment storeAttachment(InputStream in) throws IOException {
        Files.createDirectories(attachmentRoot);
        MessageDigest sha = sha256();
        Path tmp = Files.createTempFile(attachmentRoot, "upload-", ".part");
        long sizeBytes;
        try {
            try (var dis = new DigestInputStream(in, sha); var out = new FileOutputStream(tmp.toFile())) {
                sizeBytes = dis.transferTo(out);
                out.getChannel().force(true);
            }
            String hash = HexFormat.of().formatHex(sha.digest());
            Path target = attachmentRoot.resolve(hash.substring(0, 2)).resolve(hash.substring(2, 4)).resolve(hash);
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Files.deleteIfExists(tmp); // дубль — физическая копия уже есть
            } else {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target);
                } catch (FileAlreadyExistsException e) {
                    Files.deleteIfExists(tmp); // параллельная загрузка того же файла выиграла гонку
                }
            }
            return new StoredAttachment(hash, sizeBytes);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    /** Открывает .eml по относительному пути из БД, не позволяя выйти за пределы mailRoot. */
    public InputStream openMessage(String relativePath) throws IOException {
        Path resolved = resolveMessagePath(relativePath);
        return Files.newInputStream(resolved);
    }

    Path resolveMessagePath(String relativePath) {
        Path root = mailRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Путь письма выходит за пределы хранилища");
        }
        return resolved;
    }

    /**
     * Перемещает .eml между Maildir-папками одного ящика. Первый сегмент относительного пути —
     * уже санитизированный mailbox из storeMessage, поэтому владельцу не нужен повторный lookup.
     */
    public Path moveMessageToFolder(String relativePath, String targetFolder, boolean seen) throws IOException {
        Path source = resolveMessagePath(relativePath);
        Path relative = mailRoot.toAbsolutePath().normalize().relativize(source);
        if (relative.getNameCount() < 1) {
            throw new IllegalArgumentException("Некорректный путь письма");
        }
        String mailbox = relative.getName(0).toString();
        Path dir = mailRoot.resolve(mailbox).resolve(sanitize(targetFolder));
        Files.createDirectories(dir.resolve("tmp"));
        Files.createDirectories(dir.resolve("new"));
        Files.createDirectories(dir.resolve("cur"));
        String name = source.getFileName().toString();
        Path target = dir.resolve(seen ? "cur" : "new").resolve(name);
        if (Files.exists(target) && !target.equals(source)) {
            target = target.resolveSibling(System.currentTimeMillis() + "." + UUID.randomUUID() + ".eml");
        }
        if (!target.equals(source)) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target);
            }
        }
        return mailRoot.relativize(target);
    }

    /** Копия .eml в другую папку того же ящика (IMAP COPY): новое имя файла, исходник не тронут. */
    public Path copyMessageToFolder(String relativePath, String targetFolder, boolean seen) throws IOException {
        Path source = resolveMessagePath(relativePath);
        Path relative = mailRoot.toAbsolutePath().normalize().relativize(source);
        if (relative.getNameCount() < 1) {
            throw new IllegalArgumentException("Некорректный путь письма");
        }
        String mailbox = relative.getName(0).toString();
        Path dir = mailRoot.resolve(mailbox).resolve(sanitize(targetFolder));
        Files.createDirectories(dir.resolve("tmp"));
        Files.createDirectories(dir.resolve("new"));
        Files.createDirectories(dir.resolve("cur"));
        String name = System.currentTimeMillis() + "." + UUID.randomUUID() + ".eml";
        Path target = dir.resolve(seen ? "cur" : "new").resolve(name);
        Files.copy(source, target);
        return mailRoot.relativize(target);
    }

    /** Физическое удаление .eml (EXPUNGE / POP3 DELE+QUIT). Отсутствие файла — не ошибка. */
    public void deleteMessage(String relativePath) throws IOException {
        Files.deleteIfExists(resolveMessagePath(relativePath));
    }

    /**
     * Переименование каталога папки на ФС (IMAP RENAME). Пути писем в БД обновляет вызывающий
     * сервис в той же транзакции; если каталога ещё нет (папка пустая и файлов не было) — no-op.
     */
    public void renameFolderDir(String mailbox, String oldFolder, String newFolder) throws IOException {
        Path base = mailRoot.resolve(sanitize(mailbox));
        Path from = base.resolve(sanitize(oldFolder));
        Path to = base.resolve(sanitize(newFolder));
        if (!Files.exists(from) || from.equals(to)) {
            return;
        }
        if (Files.exists(to)) {
            throw new FileAlreadyExistsException(to.toString());
        }
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /** Рекурсивное удаление каталога папки (IMAP DELETE) после удаления строк БД. Best-effort. */
    public void deleteFolderDir(String mailbox, String folder) throws IOException {
        Path rootAbs = mailRoot.toAbsolutePath().normalize();
        Path dir = rootAbs.resolve(sanitize(mailbox)).resolve(sanitize(folder)).normalize();
        if (!Files.exists(dir) || !dir.startsWith(rootAbs)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort: осиротевший каталог не должен блокировать удаление папки в БД
                }
            });
        }
    }

    private static void writeAndSync(InputStream in, Path target) throws IOException {
        try (var out = new FileOutputStream(target.toFile())) {
            in.transferTo(out);
            out.getChannel().force(true);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Защита от path traversal: в путь попадают только безопасные сегменты. */
    private static String sanitize(String segment) {
        if (segment == null) {
            throw new IllegalArgumentException("Недопустимый сегмент пути: null");
        }
        String s = segment.replaceAll("[^A-Za-z0-9@._-]", "_");
        if (s.contains("..") || ".".equals(s) || s.isBlank()) {
            throw new IllegalArgumentException("Недопустимый сегмент пути: " + segment);
        }
        return s;
    }
}
