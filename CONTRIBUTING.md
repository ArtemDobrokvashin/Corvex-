# Вклад в Corvex / Contributing to Corvex

Corvex — бесплатный open-source проект под лицензией **Apache-2.0**: собственный
корпоративный почтовый сервис и чат на своих серверах. Спасибо, что помогаете!

Corvex is a free open-source project under the **Apache-2.0** license: your own
corporate mail service and chat on your own servers. Thanks for helping!

## Русский

### Что особенно нужно проекту

1. **Первый Maven/Docker прогон.** Самая ценная помощь сейчас — выполнить
   `scripts/verify.ps1` (Windows Server) или `scripts/verify.sh` (Linux) на хосте
   с JDK 17+ и Docker и прислать первую ошибку сборки/старта (без секретов).
2. **Stage 3** — IMAP/POP3 адаптеры на Apache James и проверка в Outlook/Thunderbird.
3. **Windows 7 + Chrome 109** — проверка legacy-бандла на реальном старом парке.
4. Тесты, документация, переводы и отчёты о развёртывании ([`ADOPTERS.md`](ADOPTERS.md)).

### Правила кода

- Java 17, Spring Boot; каждый сервис автономен (свой `pom.xml`, Dockerfile, БД).
  Сборка всего проекта одной командой: `mvn -B clean verify` из корня.
- **Не писать почтовые RFC-парсеры с нуля**: SMTP — SubEtha SMTP, IMAP/POP3 —
  Apache James, тесты — GreenMail.
- Frontend обязан поддерживать **Chrome 109 на Windows 7**: без современных
  API вне legacy-бандла/полифиллов; адаптивность для планшетов и телефонов обязательна.
- Интерфейс и публичные ошибки API — **RU/EN одновременно**; русский — исходный язык ключей.
- Авторизация проверяется на сервере на каждое действие; UI-скрытие кнопок — не защита.
- Internal API (`/internal/**`) никогда не публикуется через gateway и работает fail-closed.
- Никогда не коммитить `.env`, пароли, токены, приватные ключи и настоящие письма.

### Перед Pull Request

- `cd frontend && npm test && npm run build` — зелёные.
- `mvn -B clean verify` — зелёный (на хосте с JDK/Maven), включая JaCoCo-гейты модулей.
- `docker compose config -q` проходит; изменения API отражены в
  [`docs/03-api-contracts.md`](docs/03-api-contracts.md) и [`docs/api/`](docs/api/).
- Описание PR: что изменено, как проверено, какие ворота из
  [`docs/READINESS.md`](docs/READINESS.md) затронуты.

Вклад принимается под лицензией Apache-2.0 (см. [`LICENSE`](LICENSE) и [`NOTICE`](NOTICE)).

## English

### What the project needs most

1. **The first Maven/Docker run.** The most valuable help right now is running
   `scripts/verify.ps1` (Windows Server) or `scripts/verify.sh` (Linux) on a host
   with JDK 17+ and Docker and reporting the first build/startup error (no secrets).
2. **Stage 3** — Apache James IMAP/POP3 adapters and Outlook/Thunderbird acceptance.
3. **Windows 7 + Chrome 109** — verifying the legacy bundle on real legacy hardware.
4. Tests, documentation, translations, and deployment reports ([`ADOPTERS.md`](ADOPTERS.md)).

### Code rules

- Java 17, Spring Boot; every service is autonomous (own `pom.xml`, Dockerfile, DB).
  Build everything with `mvn -B clean verify` from the repository root.
- **Do not write mail RFC parsers from scratch**: SubEtha SMTP for SMTP, Apache James
  for IMAP/POP3, GreenMail in tests.
- The frontend must keep working on **Chrome 109 / Windows 7** and stay adaptive
  for tablets and phones.
- UI and public API errors ship in **RU/EN together**; Russian is the source key language.
- Authorization is enforced server-side on every action; hiding a button is not security.
- Internal APIs (`/internal/**`) are never exposed through the gateway and fail closed.
- Never commit `.env`, passwords, tokens, private keys, or real mail.

Contributions are accepted under the Apache-2.0 license (see [`LICENSE`](LICENSE)
and [`NOTICE`](NOTICE)).
