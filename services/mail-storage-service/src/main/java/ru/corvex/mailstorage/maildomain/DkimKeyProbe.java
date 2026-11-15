package ru.corvex.mailstorage.maildomain;

import java.util.Optional;

/**
 * Публичный DKIM-ключ действующего отправителя. Приватный ключ никогда не
 * покидает mail-smtp-service: наружу отдаётся только p= для DNS-записи.
 */
public interface DkimKeyProbe {

    /**
     * @param domain домен, для которого нужен ключ
     * @return ключ отправителя или empty, если для этого домена его нет
     */
    Optional<SigningKey> signingKey(String domain);

    record SigningKey(String domain, String selector, String publicKeyBase64) { }
}
