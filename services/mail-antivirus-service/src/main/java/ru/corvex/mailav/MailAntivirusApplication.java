package ru.corvex.mailav;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Клиент ClamAV (clamd, INSTREAM по TCP 3310): сканирование вложений и файлов чата.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class MailAntivirusApplication {
    public static void main(String[] args) {
        SpringApplication.run(MailAntivirusApplication.class, args);
    }
}
