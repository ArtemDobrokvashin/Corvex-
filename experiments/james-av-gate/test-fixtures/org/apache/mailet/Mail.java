package org.apache.mailet;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
public interface Mail { MimeMessage getMessage() throws MessagingException; void setState(String state); }
