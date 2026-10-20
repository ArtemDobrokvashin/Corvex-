package ru.corvex.mailimap.server;

import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.SharedByteArrayInputStream;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.ModSeq;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.ByteContent;
import org.apache.james.mailbox.model.ComposedMessageId;
import org.apache.james.mailbox.model.Content;
import org.apache.james.mailbox.model.Header;
import org.apache.james.mailbox.model.Headers;
import org.apache.james.mailbox.model.MailboxId;
import org.apache.james.mailbox.model.MessageAttachmentMetadata;
import org.apache.james.mailbox.model.MessageId;
import org.apache.james.mailbox.model.MessageMetaData;
import org.apache.james.mailbox.model.MessageResult;
import org.apache.james.mailbox.model.MimeDescriptor;
import org.apache.james.mailbox.model.MimePath;
import org.apache.james.mailbox.model.ThreadId;
import org.apache.james.mailbox.store.MimeDescriptorImpl;
import ru.corvex.mailimap.client.ImapStorageClient;

import javax.mail.Flags;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * James MessageResult поверх метаданных хранилища + лениво загружаемого сырого .eml.
 * BODYSTRUCTURE/ENVELOPE Джеймс строит из MimeDescriptor (MimeDescriptorImpl из mailbox-store),
 * FETCH BODY[]/HEADER/TEXT — из Content-методов ниже, части BODY[1.2] достаются Jakarta Mail.
 */
final class CorvexMessageResult implements MessageResult {

    private final CorvexMailboxManager manager;
    private final org.apache.james.mailbox.MailboxSession session;
    private final MailboxId mailboxId;
    private final UUID folderId;
    private final ImapStorageClient.MessageMeta meta;

    private byte[] raw;
    private MimeMessage parsed;

    CorvexMessageResult(CorvexMailboxManager manager, org.apache.james.mailbox.MailboxSession session,
                        MailboxId mailboxId, UUID folderId, ImapStorageClient.MessageMeta meta) {
        this.manager = manager;
        this.session = session;
        this.mailboxId = mailboxId;
        this.folderId = folderId;
        this.meta = meta;
    }

    ComposedMessageId composedId() {
        return new ComposedMessageId(mailboxId, getMessageId(), getUid());
    }

    @Override
    public MessageId getMessageId() {
        return CorvexMessageId.of(meta.id());
    }

    @Override
    public ThreadId getThreadId() {
        return ThreadId.fromBaseMessageId(getMessageId());
    }

    @Override
    public Optional<Date> getSaveDate() {
        return Optional.empty();
    }

    @Override
    public Date getInternalDate() {
        return Date.from(meta.internalDate());
    }

    @Override
    public Flags getFlags() {
        return JamesFlags.fromMeta(meta);
    }

    @Override
    public long getSize() {
        return meta.sizeBytes();
    }

    @Override
    public MessageMetaData messageMetaData() {
        return manager.toMessageMetaData(meta);
    }

    @Override
    public MessageUid getUid() {
        return MessageUid.of(meta.uid());
    }

    @Override
    public ModSeq getModSeq() {
        return ModSeq.of(Math.max(1, meta.modseq()));
    }

    @Override
    public MimeDescriptor getMimeDescriptor() throws MailboxException {
        try {
            return MimeDescriptorImpl.build(new ByteArrayInputStream(loadRaw()));
        } catch (Exception e) {
            throw new MailboxException("Не удалось разобрать MIME-структуру письма uid=" + meta.uid(), e);
        }
    }

    @Override
    public MailboxId getMailboxId() {
        return mailboxId;
    }

    @Override
    public Iterator<Header> iterateHeaders(MimePath path) throws MailboxException {
        if (isRoot(path)) {
            return parseHeaders(loadRaw()).iterator();
        }
        return partHeaders(findPart(path), false).iterator();
    }

    @Override
    public Iterator<Header> iterateMimeHeaders(MimePath path) throws MailboxException {
        if (isRoot(path)) {
            return parseHeaders(loadRaw()).stream()
                    .filter(h -> isMimeHeader(h.getName()))
                    .iterator();
        }
        return partHeaders(findPart(path), true).iterator();
    }

    @Override
    public Content getFullContent() throws MailboxException, IOException {
        return new ByteContent(loadRaw());
    }

    @Override
    public Content getFullContent(MimePath path) throws MailboxException {
        if (isRoot(path)) {
            return new ByteContent(loadRaw());
        }
        return new ByteContent(fullPartContent(findPart(path)));
    }

    @Override
    public Content getBody() throws MailboxException, IOException {
        return new ByteContent(bodyBytes(loadRaw()));
    }

    @Override
    public Content getBody(MimePath path) throws MailboxException {
        if (isRoot(path)) {
            return new ByteContent(bodyBytes(loadRaw()));
        }
        return new ByteContent(partBody(findPart(path)));
    }

    @Override
    public Content getMimeBody(MimePath path) throws MailboxException {
        return getBody(path);
    }

    @Override
    public Headers getHeaders() throws MailboxException {
        byte[] rawBytes = loadRaw();
        return new RawHeaders(headerBytes(rawBytes), parseHeaders(rawBytes));
    }

    @Override
    public List<MessageAttachmentMetadata> getLoadedAttachments() {
        // Вложения Джеймс для IMAP FETCH не требует списком: BODYSTRUCTURE строится из MimeDescriptor.
        return List.of();
    }

    @Override
    public int compareTo(MessageResult other) {
        return getUid().compareTo(other.getUid());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CorvexMessageResult that)) return false;
        return mailboxId.equals(that.mailboxId) && meta.id().equals(that.meta.id()) && meta.uid() == that.meta.uid();
    }

    @Override
    public int hashCode() {
        return Objects.hash(mailboxId, meta.id(), meta.uid());
    }

    // ------------------------------------------------------------------ raw loading / MIME parts

    private byte[] loadRaw() throws MailboxException {
        if (raw == null) {
            raw = manager.readRaw(session, folderId, meta);
        }
        return raw;
    }

    private MimeMessage parseMessage() throws MailboxException {
        if (parsed == null) {
            try {
                Session mailSession = Session.getInstance(new Properties());
                parsed = new MimeMessage(mailSession, new SharedByteArrayInputStream(loadRaw()));
            } catch (Exception e) {
                throw new MailboxException("Не удалось разобрать письмо uid=" + meta.uid(), e);
            }
        }
        return parsed;
    }

    private Part findPart(MimePath path) throws MailboxException {
        try {
            Part current = parseMessage();
            int[] positions = path == null ? new int[0] : path.getPositions();
            for (int i = 0; i < positions.length; i++) {
                int position = positions[i];
                Object content = current.getContent();
                if (content instanceof Multipart multipart) {
                    int index = position <= 0 ? position : position - 1;
                    if (index < 0 || index >= multipart.getCount()) {
                        throw new MailboxException("MIME-часть не найдена: " + path);
                    }
                    current = multipart.getBodyPart(index);
                } else if (content instanceof Part embedded) {
                    // message/rfc822: следующий сегмент адресует вложенное письмо и далее его части
                    current = embedded;
                    if (position != 1 && i == positions.length - 1) {
                        throw new MailboxException("MIME-часть не найдена: " + path);
                    }
                } else if (i == 0 && position == 1) {
                    // BODY[1] у одночастного письма — его единственное тело
                    break;
                } else {
                    throw new MailboxException("MIME-часть не найдена: " + path);
                }
            }
            return current;
        } catch (MailboxException e) {
            throw e;
        } catch (Exception e) {
            throw new MailboxException("Не удалось достать MIME-часть " + path + " письма uid=" + meta.uid(), e);
        }
    }

    private byte[] partBody(Part part) throws MailboxException {
        try {
            if (part instanceof MimeBodyPart mimeBodyPart) {
                try (InputStream in = mimeBodyPart.getRawInputStream()) {
                    return in == null ? new byte[0] : in.readAllBytes();
                }
            }
            if (part instanceof MimeMessage) {
                return bodyBytes(loadRaw());
            }
            try (InputStream in = part.getInputStream()) {
                return in == null ? new byte[0] : in.readAllBytes();
            }
        } catch (Exception e) {
            throw new MailboxException("Не удалось прочитать тело MIME-части письма uid=" + meta.uid(), e);
        }
    }

    private byte[] fullPartContent(Part part) throws MailboxException {
        try {
            if (part instanceof MimeMessage) {
                return loadRaw();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            java.util.Enumeration<String> lines = part.getAllHeaderLines();
            while (lines.hasMoreElements()) {
                out.write(lines.nextElement().getBytes(StandardCharsets.ISO_8859_1));
                out.write('\r');
                out.write('\n');
            }
            out.write('\r');
            out.write('\n');
            out.write(partBody(part));
            return out.toByteArray();
        } catch (MailboxException e) {
            throw e;
        } catch (Exception e) {
            throw new MailboxException("Не удалось собрать MIME-часть письма uid=" + meta.uid(), e);
        }
    }

    private List<Header> partHeaders(Part part, boolean mimeOnly) throws MailboxException {
        try {
            List<Header> headers = new ArrayList<>();
            java.util.Enumeration<jakarta.mail.Header> all = part.getAllHeaders();
            while (all.hasMoreElements()) {
                jakarta.mail.Header header = all.nextElement();
                if (!mimeOnly || isMimeHeader(header.getName())) {
                    headers.add(new Header(header.getName(), header.getValue()));
                }
            }
            return headers;
        } catch (Exception e) {
            throw new MailboxException("Не удалось прочитать заголовки MIME-части письма uid=" + meta.uid(), e);
        }
    }

    private static boolean isMimeHeader(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("content-") || lower.startsWith("mime-");
    }

    private static boolean isRoot(MimePath path) {
        return path == null || path.getPositions() == null || path.getPositions().length == 0;
    }

    static List<Header> parseHeaders(byte[] raw) {
        List<Header> headers = new ArrayList<>();
        CorvexSearch.parseHeaders(raw).forEach((name, value) -> headers.add(new Header(name, value)));
        return headers;
    }

    static byte[] headerBytes(byte[] raw) {
        int[] split = headerSplit(raw);
        int end = split[0] < 0 ? raw.length : split[0] + split[1];
        return Arrays.copyOfRange(raw, 0, end);
    }

    static byte[] bodyBytes(byte[] raw) {
        int[] split = headerSplit(raw);
        if (split[0] < 0) {
            return new byte[0];
        }
        return Arrays.copyOfRange(raw, split[0] + split[1], raw.length);
    }

    /** Позиция и длина разделителя заголовков/тела; ISO-8859-1 гарантирует 1 char = 1 byte. */
    private static int[] headerSplit(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int crlf = text.indexOf("\r\n\r\n");
        int lf = text.indexOf("\n\n");
        if (crlf >= 0 && (lf < 0 || crlf <= lf)) {
            return new int[]{crlf, 4};
        }
        if (lf >= 0) {
            return new int[]{lf, 2};
        }
        return new int[]{-1, 0};
    }

    private static final class RawHeaders implements Headers {
        private final byte[] bytes;
        private final List<Header> headerList;

        private RawHeaders(byte[] bytes, List<Header> headerList) {
            this.bytes = bytes;
            this.headerList = headerList;
        }

        @Override
        public Iterator<Header> headers() {
            return headerList.iterator();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public long size() {
            return bytes.length;
        }
    }
}
