package ru.corvex.mailspam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Клиент SpamAssassin (spamd, протокол SPAMC): скоринг, обучение sa-learn, правила per-user.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class MailSpamApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailSpamApplication.class, args);
    }
}
