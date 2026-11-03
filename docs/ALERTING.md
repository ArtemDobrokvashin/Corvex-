# Уведомления мониторинга: частота, silence и внешние каналы

## 1. Архитектура и граница независимости

Alertmanager независимо рассылает каждую группу алертов в два webhook:

```text
Prometheus → Alertmanager
              ├─→ notification-service → RabbitMQ → SMTP Corvex → внутренние получатели
              └─→ alert-relay → внешний SMTP / Telegram / MAX
```

`alert-relay` — небольшой sidecar на Python стандартной библиотеки. Он не использует
PostgreSQL, RabbitMQ, Java-сервисы или SMTP Corvex, не публикует порт на хост и доступен
только в Docker-сети `monitoring`. Поэтому отказ `notification-service`, RabbitMQ или
почтовых сервисов не блокирует внешний канал, пока работают Docker, Alertmanager и сам relay.

**Это не внешний watchdog.** При отказе всего сервера, Docker, сети, Alertmanager или relay
сообщение с того же сервера отправить некому. Для сценария «сервер полностью недоступен»
нужна отдельная проверка HTTPS/heartbeat с другой площадки (внешний Uptime Kuma,
hosted-monitoring или второй независимый watchdog). Такой внешний watchdog в Corvex пока
не развёрнут и не принят.

## 2. Частота повторов

В `.env` есть две настройки:

```dotenv
CORVEX_ALERT_REPEAT_INTERVAL=4h
CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL=30m
```

Первая управляет обычными алертами, вторая — `severity=critical`. Допустимы Prometheus
duration, например `15m`, `1h`, `6h`, `1d`. Значения проверяются `amtool check-config` до
запуска Alertmanager; ошибка не подменяется молча значением по умолчанию.

`repeat_interval` — это **повторное напоминание, пока алерт активен**, а не интервал
обнаружения сбоя. Первое уведомление определяется правилом `for` и `group_wait: 30s`;
добавление алерта в существующую группу также зависит от `group_interval: 5m`.

После изменения интервалов пересоздайте только Alertmanager:

```powershell
docker compose --profile monitoring up -d --force-recreate alertmanager
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
```

Базы, почтовые volumes и остальные сервисы эта команда не пересоздаёт.

## 3. Временное отключение уведомлений (silence)

Silence отключает **новые уведомления обоих каналов**, но не останавливает Prometheus,
Alertmanager или Grafana и не скрывает сам сбой. Состояние silence хранится в
`alertmanager-data` и переживает перезапуск контейнера.

```powershell
# Все Corvex-алерты на 60 минут.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 60

# Только один Prometheus instance на 2 часа.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 120 -Service chat-service:8088

# Активные silence, их UUID и effective repeat intervals.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status

# Досрочно завершить конкретный silence.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 unsilence -SilenceId <uuid>
```

Длительность одной операции ограничена 30 сутками; после срока доставка возобновляется
автоматически. Silence не отзывает уже отправленные письма и не удаляет события, которые
уже попали во внутреннюю очередь до его создания.

## 4. Внешняя почта напрямую из alert-relay

Используйте SMTP независимого провайдера или отдельный почтовый сервер, а не
`mail-smtp-service`. Незашифрованный SMTP для этого канала намеренно не поддерживается.

```dotenv
CORVEX_ALERT_EXTERNAL_EMAIL_ENABLED=true
CORVEX_ALERT_EXTERNAL_EMAIL_RECIPIENTS=admin@example.net,backup@example.net
CORVEX_ALERT_EXTERNAL_SMTP_HOST=smtp.example.net
CORVEX_ALERT_EXTERNAL_SMTP_PORT=587
CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE=starttls
CORVEX_ALERT_EXTERNAL_SMTP_USERNAME=corvex-alert@example.net
CORVEX_ALERT_EXTERNAL_SMTP_PASSWORD=<app-password>
CORVEX_ALERT_EXTERNAL_SMTP_FROM=corvex-alert@example.net
```

Для implicit TLS обычно используют порт `465` и `CORVEX_ALERT_EXTERNAL_SMTP_TLS_MODE=ssl`.
Если провайдер разрешает аутентифицированный relay без логина, username/password можно
оставить пустыми; TLS всё равно обязателен. Пароль приложения вводится только в защищённый
`.env`, не в командную строку, тикет или чат.

Старые `CORVEX_ALERT_RECIPIENTS` и `ALERT_SMTP_HOST` относятся только к внутреннему каналу.
Новые `CORVEX_ALERT_EXTERNAL_*` не изменяют их неявно.

## 5. Telegram

1. Создайте отдельного bot через официальный BotFather и добавьте его в нужный чат.
2. Заполните token и chat/channel ID в `.env`; для групп ID может быть отрицательным.
3. Не отправляйте token разработчику или в общий чат и не записывайте его в аргументы CLI.

```dotenv
CORVEX_ALERT_TELEGRAM_ENABLED=true
CORVEX_ALERT_TELEGRAM_BOT_TOKEN=<token>
CORVEX_ALERT_TELEGRAM_CHAT_ID=<chat-id>
```

Telegram Bot API требует token в пути URL. Relay никогда не пишет URL, тело ответа или
сам token в лог; сетевые ошибки выводятся только в sanitised-форме. HTTP-redirect для
Telegram/MAX не выполняется, чтобы credentials и текст не ушли на неожиданный адрес.

## 6. MAX

Используется актуальный официальный endpoint `https://platform-api2.max.ru/messages`.
Token передаётся только в заголовке `Authorization`, а не в query `access_token`.

```dotenv
CORVEX_ALERT_MAX_ENABLED=true
CORVEX_ALERT_MAX_BOT_TOKEN=<token>
# Заполните ровно один вариант:
CORVEX_ALERT_MAX_CHAT_ID=<chat-or-channel-id>
CORVEX_ALERT_MAX_USER_ID=
```

Token выдаётся в кабинете MAX для бизнеса/настройках бота. Если для TLS требуется цепочка
Минцифры, поместите публичный PEM в отдельный `infra/alert-ca` (или `CORVEX_ALERT_CA_DIR`) и укажите:

```dotenv
CORVEX_ALERT_EXTERNAL_CA_FILE=/etc/corvex/alert-ca/mincifry-chain.pem
```

Дополнительный CA добавляется к системным корням, а не заменяет их. Каталог монтируется
read-only и не содержит приватные ключи из `infra/certs`. Один CA-bundle можно
использовать и для частного внешнего SMTP. Relay ограничивает сообщение 3900 символами
(официальный предел MAX — 4000) и сериализует MAX-отправки с интервалом не менее 0,55 с.

## 7. Применение и проверка каналов

Все внешние каналы по умолчанию выключены. После заполнения `.env` пересоздайте только relay:

```powershell
docker compose --profile monitoring up -d --build --force-recreate alert-relay
docker compose ps alert-relay alertmanager
docker compose logs --tail 50 alert-relay
```

Несколько алертов одного Alertmanager webhook объединяются в одно внешнее сообщение.
Каналы обрабатываются независимо. Если хотя бы один внешний канал уже доставлен, relay не
заставляет Alertmanager повторять весь webhook и не дублирует успешные каналы; неудачный
канал получит следующее scheduled-уведомление. Если все включённые каналы не доставлены,
HTTP 502 оставляет попытку retry Alertmanager.

Для сквозной проверки можно использовать существующий controlled synthetic alert:

```powershell
py -3 scripts/alert_e2e.py --host mail.company.local --username admin@company.local --ca-file C:\corvex-pki\corvex-test-ca.crt --wait-seconds 180 --report artifacts\smoke\alert-e2e.json
```

Он создаёт один ограниченный по времени `CorvexAlertSmoke` и затем завершает его, не
останавливая сервисы. JSON PASS этого скрипта доказывает **внутреннюю IMAP-цепочку**;
для внешнего канала отдельно подтвердите фактическое сообщение на внешней почте, в Telegram
или MAX и отсутствие `delivery failed` в `alert-relay`. Не запускайте проверку лишний раз
и не используйте реальную остановку production-сервиса как alert-test.

## 8. Диагностика без секретов

```powershell
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
docker compose logs --tail 100 alertmanager alert-relay notification-service
```

Если entrypoint сразу пишет `set: illegal option -`, проверьте CRLF в bind-mounted
`alertmanager-entrypoint.sh`: Linux-shell воспринимает `\r` как часть опции. В репозитории
`.gitattributes` принудительно задаёт `*.sh text eol=lf`; после обновления такой скрипт должен
остаться LF даже при Windows `core.autocrlf=true`, ручная правка контейнера не требуется.

Не публикуйте `.env`, Docker Compose config с environment, SMTP password, bot token или
полные внешние HTTP-запросы. Для отчёта достаточно имени канала, HTTP-статуса и sanitised
строки ошибки.
