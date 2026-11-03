# Автоматические проверки Corvex

Скрипты разделены по уровням, чтобы сборка, запущенная инфраструктура и почтовые
протоколы проверялись независимо и давали понятный результат.

| Инструмент | Что проверяет | Меняет стенд? |
|---|---|---|
| `verify.ps1` / `verify.sh` | Frontend, production audit, Maven 12 сервисов + JaCoCo, Compose config; опционально Docker build | Собирает артефакты/образы, но не запускает и не удаляет контейнеры |
| `runtime_smoke.py` | Уже созданные контейнеры, running/healthy, HTTPS frontend | **Нет**: не выполняет `up`, `down`, restart или prune |
| `alert_e2e.py` | Synthetic Alertmanager alert → webhook/Rabbit → SMTP → IMAP INBOX | **Да, ограниченно**: создаёт и завершает один alert с run ID; не останавливает сервисы и не меняет существующие письма |
| `mail_smoke.py` | SMTP 587, IMAP 143/993, POP3 995 через стандартные клиенты Python | **Нет**: не отправляет письма и не выполняет MOVE/DELE/EXPUNGE |
| `mail_e2e.py` | SMTP send → Sent APPEND → INBOX получателя → Sent отправителя, text/HTML + SHA-256 вложения | **Да, ограниченно**: создаёт одно новое письмо и его Sent-копию с уникальным run ID; старые письма не трогает |
| `mail_persistence.py` | IMAP MOVE, flags/reconnect, restart-persistence и UID EXPUNGE для письма из e2e | **По умолчанию нет**: без флагов только precheck; полный режим требует двух явных разрешений и работает только с собственным run ID/временной папкой |

Для Python-скриптов нужен **Python 3.10+**, сторонние pip-пакеты не требуются.
Windows: `py -3`; Linux/macOS: `python3`. Все команды ниже выполняются из корня
репозитория. JSON-отчёты рекомендуется складывать в `artifacts/smoke/` — каталог
исключён из Git.

## 1. Сборочные ворота G0–G2

Windows Server:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -DockerBuild
```

Linux:

```bash
scripts/verify.sh --docker-build
```

Docker build не заменяет `mvn clean verify`: оба этапа уже включены в `verify`.

## 2. Runtime-smoke уже запущенного стека

После контролируемого `docker compose up -d`:

```powershell
py -3 scripts/runtime_smoke.py `
  --url https://mail.company.local/ `
  --ca-file C:\corvex-pki\corvex-test-ca.crt `
  --wait-seconds 180 `
  --report artifacts\smoke\runtime.json
```

Если CA уже доверен в используемом Python-окружении, `--ca-file` можно опустить.
Флагов для отключения проверки сертификата или hostname нет. Для включённого
monitoring-профиля добавьте `--monitoring`: тогда обязательны и базовые, и
monitoring-контейнеры.

Скрипт получает состав сервисов из актуального Compose config, проверяет каждый
через `docker inspect`, ждёт только состояние `health=starting` и отдельно требует
HTTP 200 + Corvex SPA `#root` по HTTPS. Опция `--skip-web` даёт статус `SKIP`, а не
скрытый PASS.

## 2A. Controlled monitoring alert → email e2e

После запуска `--profile monitoring` и настройки локальной почты укажите ящик, который
уже входит в `CORVEX_ALERT_RECIPIENTS`. Скрипт сначала проверяет эффективную настройку
и не создаёт alert, если ящик в ней отсутствует. Пароль запрашивается скрыто:

```powershell
py -3 scripts/alert_e2e.py `
  --host mail.company.local `
  --username admin@company.local `
  --ca-file C:\corvex-pki\corvex-test-ca.crt `
  --wait-seconds 180 `
  --report artifacts\smoke\alert-e2e.json
```

Для protected CI поддерживается `CORVEX_TEST_ALERT_PASSWORD`; пароль нельзя передавать
аргументом CLI. Скрипт использует `amtool` внутри существующего контейнера Alertmanager,
поэтому публиковать порт 9093 наружу не требуется. Он создаёт один
`CorvexAlertSmoke` с уникальным run ID и ограниченным сроком жизни, ждёт письмо с
учётом `group_wait`, проверяет по IMAP 993 точные Subject/body и затем завершает alert.
Resolve выполняется и после FAIL доставки; при `send_resolved: true` Alertmanager может
отправить отдельное resolved-уведомление.

Сервисы не останавливаются. INBOX открывается через `EXAMINE`, тело читается через
`BODY.PEEK[]`; существующие письма не изменяются и не удаляются. Тестовое alert-письмо
сохраняется для диагностики, а JSON содержит только технические статусы, TLS и run ID.

## 3. Read-only SMTP/IMAP/POP3 smoke

В INBOX должно быть хотя бы одно тестовое письмо, желательно с вложением. Пароль
вводится скрытым запросом — **не передавайте его аргументом командной строки**.

```powershell
py -3 scripts/mail_smoke.py `
  --host mail.company.local `
  --username user@company.local `
  --ca-file C:\corvex-pki\corvex-test-ca.crt `
  --report artifacts\smoke\mail-readonly.json
```

Для неинтерактивного запуска поддерживаются `CORVEX_TEST_USERNAME` и
`CORVEX_TEST_PASSWORD`; секрет должен поступать из защищённого хранилища CI и не
попадать в лог. Можно ограничить прогон повторяемым `--protocol`:

```powershell
py -3 scripts/mail_smoke.py --protocol smtp --protocol imap-starttls
```

### Что именно проверяется

| Протокол | Проверки |
|---|---|
| SMTP 587 | EHLO, обязательный STARTTLS, TLS 1.2/1.3 + hostname/chain, AUTH, NOOP, clean QUIT |
| IMAP 143 | STARTTLS до LOGIN, CAPABILITY, LOGIN, LIST, read-only EXAMINE INBOX, SEARCH ALL, RFC822.SIZE, BODY.PEEK[], разбор MIME и число вложений |
| IMAP 993 | Implicit TLS и тот же read-only сценарий |
| POP3 995 | Implicit TLS, USER/PASS, STAT/LIST/UIDL, RETR последнего письма, MIME, clean QUIT; вторая сессия без повторного RETR подтверждает тот же count/UIDL |

IMAP открывается через `EXAMINE` и `BODY.PEEK[]`; POP3 не отправляет `DELE`.
Содержимое письма, Subject и пароль в отчёт не записываются — только размер,
число вложений, TLS/cipher и технические статусы.

По умолчанию тело последнего письма ограничено 10 MiB. При пустом ящике или
превышении лимита протокол получает `SKIP`/exit `3`: логин не выдаётся за полный
FETCH/RETR-pass. Лимит можно осознанно изменить через `--max-message-bytes`.

## 4. Сквозной SMTP/IMAP e2e с вложением

Используйте **только два выделенных тестовых ящика**. Команда реально отправляет
одно локальное письмо и сохраняет его копию в Sent — это не read-only режим.

```powershell
py -3 scripts/mail_e2e.py `
  --host mail.company.local `
  --sender sender@company.local `
  --recipient recipient@company.local `
  --ca-file C:\corvex-pki\corvex-test-ca.crt `
  --wait-seconds 120 `
  --report artifacts\smoke\mail-e2e.json
```

Скрипт скрыто запрашивает разные пароли отправителя и получателя. Для protected CI
предусмотрены `CORVEX_TEST_SENDER_PASSWORD` и `CORVEX_TEST_RECIPIENT_PASSWORD`;
пароли по-прежнему нельзя передавать аргументами CLI.

Один прогон проверяет четыре обязательных шага:

1. SMTP 587 STARTTLS + AUTH + точный envelope sender/recipient + принятое DATA;
2. IMAP APPEND того же RAW в `Sent`, как это делает обычный почтовый клиент;
3. polling INBOX получателя до timeout и UID SEARCH по `X-Corvex-Smoke-ID`;
4. проверка Sent отправителя по тому же уникальному ID.

У найденных писем проверяются From/To, Subject, text/plain, text/html, имя вложения,
его размер и SHA-256. Более одного сообщения с тем же run ID — FAIL как дубль.
Новый UUID создаётся на каждый запуск; автоматических повторов отправки нет.

**INBOX и Sent-копии сохраняются** для следующего persistence-smoke. Run ID печатается
и попадает в JSON. Существующие сообщения не изменяются; DELE/MOVE/EXPUNGE и очистка
пока не выполняются. Если SMTP send не принят, зависимые шаги получают SKIP, а общий
результат остаётся FAIL, а не ложным PASS.

## 5. IMAP MOVE/flags/restart persistence

Этот сценарий использует **уже созданное** письмо из `mail_e2e.py`; новое SMTP-письмо
он не отправляет. Сначала выполните отдельный read-only precheck с точным run ID из
предыдущего отчёта:

```powershell
py -3 scripts/mail_persistence.py `
  --host mail.company.local `
  --sender sender@company.local `
  --recipient recipient@company.local `
  --run-id <32-hex-run-id> `
  --ca-file C:\corvex-pki\corvex-test-ca.crt `
  --report artifacts\smoke\mail-persistence-precheck.json
```

Precheck открывает INBOX получателя и Sent отправителя через IMAP 993, ищет ровно
одно письмо по `X-Corvex-Smoke-ID`, проверяет text/HTML и SHA-256 вложения. Он не
создаёт папки, не меняет flags и не перезапускает сервисы.

Полный режим запускается только после успешного precheck и отдельного решения
провести mutating/restart-проверку. Оба флага обязательны одновременно:

```powershell
py -3 scripts/mail_persistence.py `
  --host mail.company.local `
  --sender sender@company.local `
  --recipient recipient@company.local `
  --run-id <32-hex-run-id> `
  --ca-file C:\corvex-pki\corvex-test-ca.crt `
  --wait-seconds 180 `
  --allow-mutating `
  --allow-restart `
  --report artifacts\smoke\mail-persistence.json
```

Полный режим выполняет последовательно:

1. создаёт уникальную папку `Corvex-Smoke-<run-id>` и делает UID MOVE только найденного
   тестового письма из INBOX; проверяет содержимое и отсутствие исходной копии;
2. ставит `\Flagged`, проверяет FLAGGED SEARCH, закрывает соединение и после reconnect
   сравнивает UID, flags и MIME;
3. делает targeted `docker compose restart` только для `mail-storage-service`, затем
   `mail-imap-service`, ожидая healthcheck каждого; `down`, `down -v`, prune и удаление
   volumes не используются;
4. повторно проверяет moved-письмо получателя и исходную Sent-копию отправителя после
   restart, включая стабильный UID и `\Flagged` у moved-письма;
5. только после всех PASS помечает собственный UID `\Deleted`, выполняет **UID EXPUNGE
   именно этого UID**, проверяет пустую временную папку и удаляет её. Sent-копия
   отправителя сохраняется.

При любой ошибке цепочка останавливается; автоматическая очистка после FAIL не
маскирует диагностику. До cleanup разрешено удалять только тестовый UID в временной
папке, где SELECT подтвердил ровно одно сообщение. Пароли запрашиваются скрыто;
для protected CI используются те же `CORVEX_TEST_SENDER_PASSWORD` и
`CORVEX_TEST_RECIPIENT_PASSWORD`, а не аргументы CLI.

## 6. Коды завершения и отчёты

- `0` — все выбранные проверки PASS;
- `1` — хотя бы одна проверка FAIL;
- `2` — ошибка аргументов/конфигурации запуска;
- `3` — FAIL нет, но есть явный SKIP, поэтому прогон неполный.

JSON содержит UTC-время, Git SHA, длительность и результат каждой проверки. Перед
публикацией отчёт всё равно нужно просмотреть: hostname, имена контейнеров и текст
серверной ошибки могут быть чувствительными. Не прикладывайте `.env` или реальные
письма.

## 7. Проверка самих скриптов

```powershell
py -3 -m unittest discover -s scripts/tests -v
```

Тесты используют fake SMTP/IMAP/POP3 и не обращаются к реальному стенду. Они
проверяют STARTTLS до AUTH, read-only IMAP, POP3 reconnect/UIDL без DELE, точный
SMTP envelope, Sent APPEND, MIME/SHA-256 и обнаружение дублей, UID MOVE/UID EXPUNGE
с guards, reconnect/restart-оркестрацию, healthcheck-логику, фильтрацию
Compose-профилей и controlled alert→email с обязательным resolve.

## Граница автоматизации

Эти скрипты **не закрывают полный G3**. Даже после успешного полного
`mail_persistence.py` ещё нужны webmail 401/realtime, chat, backup/restore,
monitoring alert→email и реальный Chrome 109. Любой следующий mutating e2e должен
маркировать созданные письма уникальным run ID и удалять только собственные
артефакты, не затрагивая существующую почту.
