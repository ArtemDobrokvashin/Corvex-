package ru.corvex.mailstorage.external;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.mailstorage.domain.ExternalMailOAuthToken;
import ru.corvex.mailstorage.repository.ExternalMailOAuthTokenRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalMailReauthMarkerTest {

    private ExternalMailOAuthTokenRepository tokens;
    private ExternalMailReauthMarker marker;
    private ExternalMailOAuthToken row;

    @BeforeEach
    void setUp() {
        tokens = mock(ExternalMailOAuthTokenRepository.class);
        marker = new ExternalMailReauthMarker(tokens);
        row = new ExternalMailOAuthToken();
        row.setAccountId(UUID.randomUUID());
        row.setEmail("ivan@company.ru");
        row.setReauthRequired(false);
        when(tokens.findById(row.getAccountId())).thenReturn(Optional.of(row));
    }

    @Test
    void markSetsFlagAndSavesImmediately() {
        marker.mark(row.getAccountId());

        assertTrue(row.isReauthRequired());
        assertNotNull(row.getUpdatedAt());
        verify(tokens).saveAndFlush(row);
    }

    @Test
    void unknownAccountIsIgnored() {
        marker.mark(UUID.randomUUID());

        verify(tokens, never()).saveAndFlush(any());
    }

    /**
     * Признак пишется отдельной транзакцией намеренно. На стенде флаг не выставлялся,
     * хотя интерфейс уже показывал «Согласие отозвано»: {@code accessToken} бросал 409
     * в той же транзакции, и откат затирал запись. Если кто-то уберёт {@code REQUIRES_NEW}
     * «для простоты», эта проверка упадёт.
     */
    @Test
    void markCommitsIndependentlyOfCallerRollback() throws Exception {
        var method = ExternalMailReauthMarker.class.getDeclaredMethod("mark", UUID.class);
        Transactional tx = method.getAnnotation(Transactional.class);

        assertNotNull(tx, "метод обязан быть транзакционным");
        assertEquals(Propagation.REQUIRES_NEW, tx.propagation(),
                "иначе 409 откатит запись флага вместе с транзакцией вызывающего кода");
    }
}
