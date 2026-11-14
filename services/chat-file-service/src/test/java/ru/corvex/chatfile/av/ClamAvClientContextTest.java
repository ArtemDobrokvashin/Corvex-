package ru.corvex.chatfile.av;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Проверка создания бина ClamAvClient через реальный Spring-контекст.
 * Убеждается, что @Autowired конструктор выбирается корректно при наличии
 * второго конструктора с настраиваемыми таймаутами.
 */
@SpringBootTest(classes = ClamAvClient.class)
@TestPropertySource(properties = {
    "corvex.clamd.host=clamav",
    "corvex.clamd.port=3310"
})
class ClamAvClientContextTest {

    @Autowired
    private ClamAvClient clam;

    @Test
    void springContextCreatesClamAvClientBean() {
        assertNotNull(clam);
        assertSame(ClamAvClient.class, clam.getClass());
    }
}
