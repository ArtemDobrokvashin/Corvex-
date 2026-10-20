package ru.corvex.mailpop3;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * POP3 (110/995, STLS) для устаревших клиентов — адаптер над Apache James POP3 protocol library.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class MailPop3Application {
    public static void main(String[] args) {
        SpringApplication.run(MailPop3Application.class, args);
    }
}
