package ru.corvex.mailstorage.maildomain;

import java.util.Optional;

/**
 * Публичный DKIM-ключ действующего отправителя. Приватный ключ никогда не
 * покидает mail-smtp-service: наружу отдаётся только p= для DNS-записи.
 */
public interface DkimKeyProbe {

    /** @return ключ отправителя или empty, если он недоступен/не настроен. */
    Optional<SigningKey> signingKey();

    record SigningKey(String domain, String selector, String publicKeyBase64) { }
}
