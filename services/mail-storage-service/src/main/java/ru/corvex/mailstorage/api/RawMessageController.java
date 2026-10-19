package ru.corvex.mailstorage.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import ru.corvex.mailstorage.security.InternalTokenGuard;
import ru.corvex.mailstorage.storage.RawMessageSpool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;

/**
 * Internal API сырого spool-а: SMTP пишет .eml, spam/AV читают его по rawRef.
 * Gateway сюда не маршрутизирует; доступ только из app-сети + X-Corvex-Internal-Token.
 */
@RestController
@RequestMapping("/internal/raw")
public class RawMessageController {

    private final RawMessageSpool spool;
    private final InternalTokenGuard tokens;

    public RawMessageController(RawMessageSpool spool, InternalTokenGuard tokens) {
        this.spool = spool;
        this.tokens = tokens;
    }

    public record StoredRawResponse(String rawRef, String sha256, long sizeBytes) {
    }

    @PostMapping(consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    ResponseEntity<StoredRawResponse> store(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            HttpServletRequest request) throws IOException {
        tokens.verify(token);
        try {
            RawMessageSpool.StoredRawMessage stored = spool.store(request.getInputStream());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new StoredRawResponse(stored.rawRef(), stored.sha256(), stored.sizeBytes()));
        } catch (RawMessageSpool.MessageTooLargeException e) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
        }
    }

    @GetMapping("/{rawRef}")
    ResponseEntity<InputStreamResource> open(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable String rawRef) throws IOException {
        tokens.verify(token);
        try {
            long size = spool.size(rawRef);
            InputStream in = spool.open(rawRef);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .contentLength(size)
                    .body(new InputStreamResource(in));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (NoSuchFileException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Raw message not found");
        }
    }

    @DeleteMapping("/{rawRef}")
    ResponseEntity<Void> delete(
            @RequestHeader(value = InternalTokenGuard.HEADER, required = false) String token,
            @PathVariable String rawRef) throws IOException {
        tokens.verify(token);
        try {
            spool.delete(rawRef);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
