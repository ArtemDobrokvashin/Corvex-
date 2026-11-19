package ru.corvex.mailstorage.external;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.corvex.mailstorage.repository.ExternalMailOAuthTokenRepository;

import java.time.Instant;
import java.util.UUID;

/**
 * Отдельная транзакция для признака «нужно повторное согласие».
 *
 * <p>Зачем отдельный бин: {@link ExternalMailOAuthTokenService#accessToken} помечает ящик и
 * сразу выбрасывает {@code 409}. Если писать признак в той же транзакции, выброшенное
 * исключение откатывает её вместе с записью — на стенде это и произошло: интерфейс уже
 * показывал «Согласие отозвано», а в базе {@code reauth_required} оставался {@code f},
 * потому что откат затирал флаг. Тот же откат случается и в вызывающем
 * {@code testStored}, который тоже транзакционный.
 *
 * <p>Поэтому запись идёт в {@code REQUIRES_NEW}: она фиксируется независимо от того,
 * что вызывающий код затем откатит. Вызывать этот метод нужно <b>только</b> через
 * инжектированный бин, а не изнутри того же класса: self-invocation не проходит через
 * прокси и не создаёт новую транзакцию.
 */
@Service
public class ExternalMailReauthMarker {

    private static final Logger log = LoggerFactory.getLogger(ExternalMailReauthMarker.class);

    private final ExternalMailOAuthTokenRepository tokens;

    public ExternalMailReauthMarker(ExternalMailOAuthTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void mark(UUID accountId) {
        tokens.findById(accountId).ifPresent(row -> {
            row.setReauthRequired(true);
            row.setUpdatedAt(Instant.now());
            tokens.saveAndFlush(row);
            log.warn("Ящик {} требует повторного согласия OAuth", row.getEmail());
        });
    }
}
