package ru.corvex.mailstorage.james;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Disconnected experimental coordinator; never used by the mail pipeline.
 * Caller must verify owner, active mapping, scan provenance and routing before
 * invoking. Transport must submit exactly once, synchronously consume the input,
 * and return a response attributable to that operation, or throw. No retry or
 * spool deletion is performed here. Not yet a production delivery implementation.
 */
final class JamesDeliveryCoordinator {
    static final int MAX_BYTES = 25 * 1024 * 1024;
    interface Transport {
        String appendOnce(String account, String folder, InputStream frozenBytes, long size) throws IOException;
    }
    record Request(String rawRef, UUID owner, String account, String folder, String sha256, long size) { }
    enum Outcome { CONFIRMED, ALREADY_CONFIRMED }
    static final class Uncertain extends IOException {
        private static final long serialVersionUID = 1L;
        private final UUID operationId;
        Uncertain(UUID id) { super("James delivery outcome uncertain; automatic retry prohibited"); this.operationId = id; }
        UUID operationId() { return operationId; }
    }
    private final JamesDeliveryJournal journal;
    private final Transport transport;

    JamesDeliveryCoordinator(JamesDeliveryJournal journal, Transport transport) {
        this.journal = Objects.requireNonNull(journal);
        this.transport = Objects.requireNonNull(transport);
    }

    Outcome deliver(Request request, InputStream source) throws IOException {
        Objects.requireNonNull(request); Objects.requireNonNull(source);
        if (request.size() < 0 || request.size() > MAX_BYTES || request.sha256() == null
                || !request.sha256().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid delivery bytes");
        // Read once before reservation and send this exact bounded copy. A digest
        // check followed by reopening a mutable spool path would be a TOCTOU bug.
        var bytes = readVerified(source, request.size(), request.sha256());
        var reservation = journal.reserve(request.rawRef(), request.owner(), request.account(), request.folder(),
                request.sha256(), request.size());
        if ("confirmed".equals(reservation.phase())) return Outcome.ALREADY_CONFIRMED;
        if (!"reserved".equals(reservation.phase())) throw new Uncertain(reservation.intent().operationId());
        // If begin commits but its response is lost, the persisted 'submitting'
        // state blocks retries. A failure here never starts the remote request.
        var submission = journal.begin(reservation.intent());
        final String remoteId;
        try {
            var frozen = new ByteArrayInputStream(bytes);
            remoteId = transport.appendOnce(request.account(), request.folder(), frozen, bytes.length);
            if (frozen.available() != 0) throw new IOException("Incomplete append input");
            if (remoteId == null || remoteId.isBlank() || remoteId.length() > 1024) throw new IOException("Missing append response");
        } catch (IOException | RuntimeException failure) {
            try { journal.uncertain(submission); } catch (RuntimeException ignored) { /* submitting is also blocked */ }
            throw new Uncertain(reservation.intent().operationId());
        }
        try {
            journal.confirm(submission, remoteId);
        } catch (RuntimeException failure) {
            // Confirmation may have committed despite the exception; no replay.
            throw new Uncertain(reservation.intent().operationId());
        }
        return Outcome.CONFIRMED;
    }

    private static byte[] readVerified(InputStream source, long expected, String expectedHash) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        var output = new ByteArrayOutputStream((int) expected);
        byte[] buffer = new byte[8192];
        long count = 0;
        int n;
        while ((n = source.read(buffer)) != -1) {
            if (n == 0) throw new IOException("Invalid spool stream");
            count += n;
            if (count > expected) throw new IOException("Delivery size mismatch");
            digest.update(buffer, 0, n);
            output.write(buffer, 0, n);
        }
        if (count != expected || !MessageDigest.isEqual(HexFormat.of().parseHex(expectedHash), digest.digest())) {
            throw new IOException("Delivery bytes mismatch");
        }
        return output.toByteArray();
    }
}
