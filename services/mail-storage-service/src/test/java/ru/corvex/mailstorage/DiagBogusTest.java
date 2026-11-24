package ru.corvex.mailstorage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagBogusTest {
    @Test
    void alwaysFails() {
        assertEquals(1, 2);
    }
}
