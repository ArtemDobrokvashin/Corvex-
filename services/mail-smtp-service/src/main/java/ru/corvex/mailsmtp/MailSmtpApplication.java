package ru.corvex.mailsmtp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SMTP submit (587) и relay (25) на базе SubEtha SMTP; очередь исходящих в RabbitMQ.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class MailSmtpApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailSmtpApplication.class, args);
    }
}
