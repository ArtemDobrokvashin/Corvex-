package ru.corvex.notification;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationHealthConfigTest {

    @Test
    void mailHealthIndicatorDoesNotDependOnOptionalAlertRelay() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("notification", new ClassPathResource("application.yml"));
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);

        assertEquals(Boolean.FALSE,
                environment.getProperty("management.health.mail.enabled", Boolean.class));
    }
}
