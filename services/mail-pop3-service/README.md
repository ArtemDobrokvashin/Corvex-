# mail-pop3-service

POP3 (RFC 1939) для устаревших клиентов: порты 110 (plain, только по явному включению) / 995 (implicit TLS).

## Статус этапа 3

Полный PC-pass **G1 (Maven) и G2 (Docker/Spring)** получен на **`8cccaee`** с
Boot 3.5.16; G2 проверен на существующих volumes, clean-volume install отдельно не
принят. На **`d75fb97`** пройден ограниченный клиентский G3-smoke в Thunderbird:
implicit TLS 995 без per-server exception, LOGIN/STAT/LIST/UIDL/RETR, сохранение копии
на сервере, повторная проверка без дублей и стабильный UIDL после restart. Это не
закрывает TOP, DELE без QUIT, app-password/2FA, большие ящики и Outlook.

Для новых установок сервис по умолчанию остаётся выключенным
(`CORVEX_POP3_ENABLED=false`): включение относится к изолированному стенду и release
gates, а не к production MX. В POM зафиксирован `james.version=3.8.2`; переход на
согласованную поддерживаемую James/JDK-платформу — отдельный G4-апгрейд. Подробности:
[`../../docs/READINESS.md`](../../docs/READINESS.md) и
[`../../docs/RELEASE-DEPENDENCIES.md`](../../docs/RELEASE-DEPENDENCIES.md).

## Как устроен

- Протокольный движок — **Apache James 3.8.2 protocols-pop3** (USER/PASS/STAT/LIST/UIDL/
  RETR/TOP/DELE/RSET/NOOP/QUIT/CAPA) поверх Netty-транспорта `protocols-netty`. Собственный
  парсер RFC не писался. Guice-инфраструктура James (`POP3Server`/`ProtocolHandlerLoader`)
  не используется: цепочка хендлеров собирается вручную в `JamesPop3Server`, проводку
  расширяемых хендлеров (CommandDispatcher, Capa) делает `ListProtocolHandlerChain`.
  Netty pipeline содержит обязательный `ChunkedWriteHandler` для потоковых RETR/TOP;
  основной обработчик зарегистрирован как `coreHandler`, который James ищет при
  endSession/QUIT. Сквозной socket-тест предотвращает возврат дефекта «есть +OK,
  но нет тела письма».
- Corvex добавляет только два адаптера: `CorvexPassCmdHandler` (пароль проверяет
  `auth-service`: основной без TOTP или app-password) и `CorvexPop3Mailbox` поверх
  внутреннего протокольного API `mail-storage-service` (`/internal/protocol/...`).
- Семантика: видна только папка INBOX; сессия работает со снапшотом на момент логина;
  `UIDL` = UUID строки `message_metadata` (стабилен между сессиями, «оставлять на сервере»
  в клиенте работает корректно); `DELE` применяется Джеймсом на `QUIT` — обрыв соединения
  без QUIT ничего не удаляет, как требует RFC.
- STLS в этой итерации **не анонсируется** (в цепочке нет `StlsCmdHandler`): клиентам —
  implicit TLS 995. Открытый 110 биндится только при `CORVEX_POP3_PLAIN_ENABLED=true`.

## TLS-сертификат (PEM -> PKCS12)

Netty-слушатель 995 читает PKCS12-хранилище `CORVEX_POP3_KEYSTORE_PATH` (в compose по
умолчанию `/certs/pop3.p12`). Из общих PEM-файлов тома `infra/certs`:

```bash
openssl pkcs12 -export -in infra/certs/fullchain.pem -inkey infra/certs/privkey.pem \
  -out infra/certs/pop3.p12 -name corvex-pop3 -passout pass:<пароль>
```

Пароль задаётся `CORVEX_POP3_KEYSTORE_PASSWORD` в `.env`. Нет файла — TLS-слушатель не
поднимется (в логе будет предупреждение), plain при этом не включается сам по себе.

## Включение и дымовой тест

```bash
# .env: CORVEX_POP3_ENABLED=true (+ при необходимости CORVEX_POP3_PLAIN_ENABLED=true)
docker compose up -d mail-pop3-service
openssl s_client -connect localhost:995 -crlf -quiet
USER ivanov@corp.local
PASS <пароль или app-password>
STAT
UIDL
RETR 1
QUIT
```

## Фактическая проверка и оставшаяся матрица

На `d75fb97` модульный прогон завершился `BUILD SUCCESS`: **44/44 теста**, включая
реальный socket `USER/PASS → STAT → RETR → QUIT`; targeted Docker build и запуск также
успешны. Thunderbird подтвердил CAPA и передачу тел писем по TLS 995.

Остаются TOP, DELE без QUIT и DELE + QUIT как отдельная негативная матрица,
app-password при 2FA, большой INBOX у границы `CORVEX_POP3_MAX_MESSAGES` (по умолчанию
1000 писем на сессию), нагрузочный профиль и Outlook. Outlook сейчас отложен и не
заявляется совместимым по результатам Thunderbird.

## Проверка модуля

Сначала модуль собирается без публичного включения порта:

```bash
mvn -B -pl :mail-pop3-service -am -DskipTests compile
mvn -B -pl :mail-pop3-service -am test
```

Затем на тестовом ящике проверьте STAT/LIST/UIDL/RETR/TOP, стабильность UIDL
между сессиями, «оставлять сообщения на сервере», DELE без QUIT (ничего не
удаляется), DELE + QUIT (удаляется ровно отмеченное), app-password при 2FA и
большой INBOX у границы `CORVEX_POP3_MAX_MESSAGES`.

Следующие задачи: клиентские интеграционные тесты, STLS только после
отдельного security review, нагрузочный профиль больших ящиков и согласованный
G4-апгрейд James/JDK. Правила вклада: [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).
