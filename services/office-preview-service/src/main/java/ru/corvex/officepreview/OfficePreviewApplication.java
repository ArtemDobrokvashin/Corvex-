package ru.corvex.officepreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Внутренний сервис: принимает байты Office-документа и отдаёт PDF.
 * Наружу не публикуется — маршрута в gateway нет.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class OfficePreviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfficePreviewApplication.class, args);
    }
}
