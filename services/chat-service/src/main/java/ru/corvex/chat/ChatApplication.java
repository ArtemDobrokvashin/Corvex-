package ru.corvex.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Чат реального времени: Spring WebSocket + STOMP, брокер-реле RabbitMQ, присутствие в Redis.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class ChatApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
