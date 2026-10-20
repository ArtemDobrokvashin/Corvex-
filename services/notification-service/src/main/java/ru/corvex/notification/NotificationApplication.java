package ru.corvex.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Email-алерты мониторинга и служебные уведомления; потребление событий из RabbitMQ, дедупликация и эскалация.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
