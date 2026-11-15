package ru.corvex.mailstorage.james;

/**
 * Узкий контракт административного моста James для доменов.
 *
 * <p>James отвечает на PUT /domains/{domain} кодом 204 и для нового домена, и
 * для уже существующего; 400 означает отказ. Любой другой код, таймаут или
 * ошибка транспорта — UNCERTAIN: вызывающая сторона обязана сохранить это
 * состояние и проверять наличие домена чтением, а не повторять PUT вслепую.
 */
public interface JamesDomainAdmin {

    enum CreateOutcome { PRESENT, REJECTED, UNCERTAIN }

    enum Presence { PRESENT, ABSENT, UNKNOWN }

    CreateOutcome create(String domain);

    Presence presence(String domain);
}
