package ru.corvex.mailstorage.mime;

import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** Extracts one unambiguous CID image from the owner-checked original message. */
public class InlineImageExtractor {
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000L;
    private static final int MAX_PARTS = 300;
    private static final int MAX_DEPTH = 20;
    public record Image(byte[] bytes, String contentType) { }

    public Image extract(InputStream input, String rawCid) throws IOException, MessagingException {
        String cid = normalizeCid(rawCid);
        if (cid == null) return null;
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), input);
        List<Part> matches = new ArrayList<>();
        collect(message, cid, matches, 0, new int[]{0});
        if (matches.size() != 1) return null;
        Part part = matches.get(0);
        String type = part.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!List.of("image/png", "image/jpeg", "image/gif").contains(type)) return null;
        byte[] bytes;
        try (InputStream content = part.getInputStream()) {
            bytes = content.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES || bytes.length == 0 || !validRaster(bytes, type)) return null;
        return new Image(bytes, type);
    }

    private void collect(Part part, String cid, List<Part> matches, int depth, int[] count) throws IOException, MessagingException {
        if (++count[0] > MAX_PARTS || depth > MAX_DEPTH) throw new IOException("MIME structure exceeds limit");
        String[] ids = part.getHeader("Content-ID");
        if (ids != null) {
            for (String id : ids) {
                if (cid.equals(normalizeCid(id))) matches.add(part);
            }
        }
        if (part.isMimeType("multipart/*")) {
            Object content = part.getContent();
            if (content instanceof Multipart multi) {
                for (int i = 0; i < multi.getCount(); i++) collect(multi.getBodyPart(i), cid, matches, depth + 1, count);
            }
        }
        // Do not traverse message/rfc822: embedded messages are not part of this document's CID scope.
    }

    private static String normalizeCid(String value) {
        if (value == null || value.length() > 512) return null;
        String cid = value.trim();
        if (cid.startsWith("<") && cid.endsWith(">")) cid = cid.substring(1, cid.length() - 1);
        if (cid.isBlank() || cid.chars().anyMatch(ch -> ch <= 32 || ch >= 127 || ch == '<' || ch == '>')) return null;
        return cid;
    }

    private boolean validRaster(byte[] bytes, String type) throws IOException {
        try (ImageInputStream image = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (image == null) return false;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(image);
            if (!readers.hasNext()) return false;
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!((type.equals("image/jpeg") && (format.equals("jpeg") || format.equals("jpg")))
                        || (type.equals("image/png") && format.equals("png"))
                        || (type.equals("image/gif") && format.equals("gif")))) return false;
                reader.setInput(image, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                return width > 0 && height > 0 && (long) width * height <= MAX_PIXELS;
            } finally { reader.dispose(); }
        } catch (IOException | RuntimeException e) { return false; }
    }
}
