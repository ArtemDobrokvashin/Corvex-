package ru.corvex.mailstorage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.elasticsearch.repository.config.EnableElasticsearchRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Метаданные писем (PostgreSQL), тела/вложения — Maildir + контентно-адресуемое хранилище, индексация в Elasticsearch.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(basePackages = "ru.corvex.mailstorage.repository")
@EnableElasticsearchRepositories(basePackages = "ru.corvex.mailstorage.search")
public class MailStorageApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailStorageApplication.class, args);
    }
}
