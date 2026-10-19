package ru.corvex.mailimap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * IMAP4rev1 (143/993, STARTTLS, IDLE) — адаптер над Apache James IMAP protocol library.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class MailImapApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailImapApplication.class, args);
    }
}
