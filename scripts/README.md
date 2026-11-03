# Автоматические проверки Corvex

Скрипты разделены по уровням, чтобы сборка, запущенная инфраструктура и почтовые
протоколы проверялись независимо и давали понятный результат.

| Инструмент | Что проверяет | Меняет стенд? |
|---|---|---|
| `verify.ps1` / `verify.sh` | Frontend, production audit, Maven 12 сервисов + JaCoCo, Compose config; опционально Docker build | Собирает артефакты/образы, но не запускает и не удаляет контейнеры |
| `runtime_smoke.py` | Уже созданные контейнеры, running/healthy, HTTPS frontend | **Нет**: не выполняет `up`, `down`, restart или prune |
| `mail_smoke.py` | SMTP 587, IMAP 143/993, POP3 995 через стандартные клиенты Python | **Нет**: не отправляет письма и не выполняет MOVE/DELE/EXPUNGE |

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

## 4. Коды завершения и отчёты

- `0` — все выбранные проверки PASS;
- `1` — хотя бы одна проверка FAIL;
- `2` — ошибка аргументов/конфигурации запуска;
- `3` — FAIL нет, но есть явный SKIP, поэтому прогон неполный.

JSON содержит UTC-время, Git SHA, длительность и результат каждой проверки. Перед
публикацией отчёт всё равно нужно просмотреть: hostname, имена контейнеров и текст
серверной ошибки могут быть чувствительными. Не прикладывайте `.env` или реальные
письма.

## 5. Проверка самих скриптов

```powershell
py -3 -m unittest discover -s scripts/tests -v
```

Тесты используют fake SMTP/IMAP/POP3 и не обращаются к реальному стенду. Они
проверяют STARTTLS до AUTH, read-only IMAP, POP3 reconnect/UIDL без DELE,
healthcheck-логику и фильтрацию Compose-профилей.

## Граница автоматизации

Эти скрипты **не закрывают полный G3**. Отдельными e2e-наборами на выделенных
тестовых ящиках ещё нужны SMTP send → доставка → Sent, IMAP MOVE/EXPUNGE,
restart-persistence, webmail 401/realtime, chat, backup/restore, monitoring
alert→email и реальный Chrome 109. Mutating e2e должен маркировать созданные
письма уникальным run ID и удалять только собственные артефакты, не затрагивая
существующую почту.
