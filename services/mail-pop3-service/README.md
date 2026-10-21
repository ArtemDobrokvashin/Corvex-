# mail-pop3-service

POP3 (RFC 1939) для устаревших клиентов: порты 110 (plain, только по явному включению) / 995 (implicit TLS).

## Статус этапа 3

Код написан, **runtime ещё не доказан**, поэтому сервис по умолчанию выключен
(`CORVEX_POP3_ENABLED=false`) до G1 (`mvn -B clean verify`), G2 (Docker/Spring)
и G3 (клиентский smoke). В POM зафиксирован `james.version=3.8.2`; это baseline
первой компиляции, а переход на James 3.9.x должен быть отдельным G4-апгрейдом
после зелёного прогона. Подробности: [`../../docs/READINESS.md`](../../docs/READINESS.md)
и [`../../docs/RELEASE-DEPENDENCIES.md`](../../docs/RELEASE-DEPENDENCIES.md).

> **Help wanted:** если у вас есть JDK 17/Maven и тестовый почтовый клиент,
> ваш первый сеанс к 995 — самая полезная проверка этого сервиса. Нужен даже
> отрицательный результат, если в issue есть SHA, версии инструментов и первая
> ошибка. Не используйте POP3 с рабочими ящиками до прохождения G3.

## Как устроен

- Протокольный движок — **Apache James 3.8.2 protocols-pop3** (USER/PASS/STAT/LIST/UIDL/
  RETR/TOP/DELE/RSET/NOOP/QUIT/CAPA) поверх Netty-транспорта `protocols-netty`. Собственный
  парсер RFC не писался. Guice-инфраструктура James (`POP3Server`/`ProtocolHandlerLoader`)
  не используется: цепочка хендлеров собирается вручную в `JamesPop3Server`, проводку
  расширяемых хендлеров (CommandDispatcher, Capa) делает `ListProtocolHandlerChain`.
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

## Что ещё не проверено

Компиляция против James 3.8.2 (конструкторы хендлеров взяты из исходников тега
`james-project-3.8.2`, но сборкой не подтверждены), поведение Netty-цепочки
`DelimiterBasedFrameDecoder -> BasicChannelInboundHandler` на реальных клиентах
(Thunderbird/Outlook), CAPA-ответ и поведение при больших ящиках
(`CORVEX_POP3_MAX_MESSAGES`, по умолчанию 1000 писем на сессию).

## Как помочь этому сервису прямо сейчас

Сначала соберите модуль без публичного включения порта:

```bash
mvn -B -pl :mail-pop3-service -am -DskipTests compile
mvn -B -pl :mail-pop3-service -am test
```

Затем на тестовом ящике проверьте STAT/LIST/UIDL/RETR/TOP, стабильность UIDL
между сессиями, «оставлять сообщения на сервере», DELE без QUIT (ничего не
удаляется), DELE + QUIT (удаляется ровно отмеченное), app-password при 2FA и
большой INBOX у границы `CORVEX_POP3_MAX_MESSAGES`.

Хорошие следующие задачи: клиентские интеграционные тесты, STLS только после
отдельного security review, нагрузочный профиль больших ящиков и G4-апгрейд
James 3.9.x. Правила вклада: [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).
