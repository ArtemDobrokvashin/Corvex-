package ru.corvex.mailstorage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Метаданные писем (PostgreSQL), тела/вложения — Maildir + контентно-адресуемое хранилище, индексация в Elasticsearch.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class MailStorageApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailStorageApplication.class, args);
    }
}
