package ru.corvex.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Быстрая проверка точки входа без старта контекста и внешних зависимостей. */
class ChatApplicationTest {

    @Test
    void applicationClassDeclaresSpringBootEntryPoint() throws Exception {
        assertNotNull(new ChatApplication());
        assertNotNull(ChatApplication.class.getAnnotation(SpringBootApplication.class));

        Method main = ChatApplication.class.getMethod("main", String[].class);
        assertTrue(Modifier.isPublic(main.getModifiers()));
        assertTrue(Modifier.isStatic(main.getModifiers()));
    }
}
