package ru.corvex.chatfile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Загрузка/раздача файлов чата: потоковая загрузка, SHA-256, предпросмотр изображений, AV-проверка перед публикацией.
 * Сервис автономен: своя БД (если применимо), миграции Flyway, метрики /actuator/prometheus.
 */
@SpringBootApplication
public class ChatFileApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatFileApplication.class, args);
    }
}
