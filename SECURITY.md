# Политика безопасности / Security Policy

## Русский

Corvex находится в стадии pre-1.0 и **пока не является production-ready** (см.
[`docs/READINESS.md`](docs/READINESS.md)): не направляйте на него боевые MX-записи
и не переносите настоящие ящики до прохождения ворот готовности. SMTP по умолчанию
выключен (`CORVEX_SMTP_ENABLED=false`).

### Как сообщить об уязвимости

Пожалуйста, **не открывайте публичный issue** для уязвимостей. Сообщите приватно:

- через GitHub Security Advisories: вкладка **Security → Report a vulnerability**
  в этом репозитории (если доступна), либо
- напрямую автору: [https://t.me/Artem_Dobrokvashin](https://t.me/Artem_Dobrokvashin).

Укажите версию/коммит, затронутый сервис, шаги воспроизведения и влияние.
Не прикладывайте настоящие письма, пароли, `.env`, токены и приватные ключи —
достаточно синтетического примера. Ответ ожидается в течение нескольких дней;
публичное раскрытие — после исправления или по согласованию.

### Поддерживаемые версии

Исправления безопасности принимаются только в актуальную ветку разработки
до первого релиза 1.0.

## English

Corvex is pre-1.0 and **not production-ready yet** (see
[`docs/READINESS.md`](docs/READINESS.md)): do not point production MX records at it
or migrate real mailboxes until the readiness gates pass. SMTP is disabled by
default (`CORVEX_SMTP_ENABLED=false`).

Please **do not open a public issue** for vulnerabilities. Report privately via
GitHub Security Advisories (**Security → Report a vulnerability**, if available)
or directly to the author at [https://t.me/Artem_Dobrokvashin](https://t.me/Artem_Dobrokvashin).
Include the version/commit, affected service, reproduction steps, and impact.
Never attach real mail, passwords, `.env`, tokens, or private keys — a synthetic
example is enough. Security fixes target the current development branch until 1.0.
