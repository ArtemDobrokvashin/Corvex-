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
        Path dir = mailRoot.resolve(sanitize(mailbox)).resolve(sanitize(folder));
        Files.createDirectories(dir.resolve("tmp"));
        Files.createDirectories(dir.resolve("new"));
        Files.createDirectories(dir.resolve("cur"));

        String name = System.currentTimeMillis() + "." + UUID.randomUUID() + ".eml";
        Path tmp = dir.resolve("tmp").resolve(name);
        try {
            writeAndSync(eml, tmp);
            Path target = dir.resolve("new").resolve(name);
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

    /** Сохраняет вложение; возвращает SHA-256 (он же ключ дедупликации и FK в mail_db.attachments). */
    public String storeAttachment(InputStream in) throws IOException {
        Files.createDirectories(attachmentRoot);
        MessageDigest sha = sha256();
        Path tmp = Files.createTempFile(attachmentRoot, "upload-", ".part");
        try {
            try (var dis = new DigestInputStream(in, sha); var out = new FileOutputStream(tmp.toFile())) {
                dis.transferTo(out);
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
            return hash;
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
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
