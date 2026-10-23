package ru.corvex.mailsmtp.outbound;

import org.subethamail.smtp.AuthenticationHandlerFactory;
import org.subethamail.smtp.MessageContext;
import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.MessageHandlerFactory;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.server.SMTPServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Настоящий SubEtha SMTP-сервер как тестовый двойник удалённого MX/SMTP провайдера.
 * Поведение RCPT/DATA настраивается адресами вида bad@... и temp@....
 */
final class FakeSmtpServer implements AutoCloseable {

    private final SMTPServer server;
    private final List<String> senders = new CopyOnWriteArrayList<>();
    private final List<String> recipients = new CopyOnWriteArrayList<>();
    private final List<byte[]> messages = new CopyOnWriteArrayList<>();
    private final AtomicInteger dataAttempts = new AtomicInteger();

    volatile int rejectDataWithCode;
    volatile int rejectFirstDataWithCode;

    FakeSmtpServer(int port, InetAddress bindAddress, AuthenticationHandlerFactory authFactory) {
        SMTPServer.Builder builder = SMTPServer.port(port)
                .hostName("mx.test.invalid")
                .messageHandlerFactory(new MessageHandlerFactory() {
                    @Override
                    public MessageHandler create(MessageContext ctx) {
                        return new MessageHandler() {
                            @Override
                            public void from(String from) {
                                senders.add(from);
                            }

                            @Override
                            public void recipient(String recipient) throws RejectException {
                                recipients.add(recipient);
                                if (recipient.contains("bad@")) {
                                    throw new RejectException(550, "5.1.1 No such user");
                                }
                                if (recipient.contains("temp@")) {
                                    throw new RejectException(451, "4.7.1 Try again later");
                                }
                            }

                            @Override
                            public String data(InputStream data) throws RejectException, IOException {
                                int attempt = dataAttempts.incrementAndGet();
                                byte[] bytes = data.readAllBytes();
                                if (rejectFirstDataWithCode != 0 && attempt == 1) {
                                    throw new RejectException(rejectFirstDataWithCode, "Temporary DATA failure");
                                }
                                if (rejectDataWithCode != 0) {
                                    throw new RejectException(rejectDataWithCode, "DATA rejected by test server");
                                }
                                messages.add(bytes);
                                return null;
                            }

                            @Override
                            public void done() {
                            }
                        };
                    }
                })
                .enableTLS(false)
                .requireTLS(false);
        if (bindAddress != null) {
            builder.bindAddress(bindAddress);
        }
        if (authFactory != null) {
            builder.authenticationHandlerFactory(authFactory);
        }
        this.server = builder.build();
        this.server.start();
    }

    int port() {
        return server.getPortAllocated();
    }

    List<String> senders() {
        return List.copyOf(senders);
    }

    List<String> recipients() {
        return List.copyOf(recipients);
    }

    List<String> messageTexts() {
        return messages.stream().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).toList();
    }

    int dataAttempts() {
        return dataAttempts.get();
    }

    void reset() {
        senders.clear();
        recipients.clear();
        messages.clear();
        dataAttempts.set(0);
        rejectDataWithCode = 0;
        rejectFirstDataWithCode = 0;
    }

    @Override
    public void close() {
        server.stop();
    }
}
