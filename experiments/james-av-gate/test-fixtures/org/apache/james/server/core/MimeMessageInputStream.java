package org.apache.james.server.core;
import java.io.ByteArrayInputStream;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
public class MimeMessageInputStream extends ByteArrayInputStream {
    public MimeMessageInputStream(MimeMessage message) throws MessagingException { super(new byte[]{1, 2}); }
}
