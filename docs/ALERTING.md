# Уведомления мониторинга: получатели, частота, категории и внешние каналы

## 1. Архитектура и граница независимости

Alertmanager независимо рассылает каждую группу алертов в два webhook:

```text
Prometheus → Alertmanager
              ├─→ notification-service → RabbitMQ → SMTP → внутренние получатели
              └─→ alert-relay → внешний SMTP / Telegram / MAX

Control plane (не delivery path):
admin UI → admin-service/admin_db → internal config API → оба consumer
```

`alert-relay` — sidecar на Python стандартной библиотеки. **Доставка** внешних сообщений не
использует PostgreSQL, RabbitMQ, Java-сервисы или SMTP Corvex. Для получения настроек relay
обращается к admin-service, но сохраняет последнюю валидную конфигурацию в
`alert-relay-data` с правами `0600`. Поэтому после первой успешной загрузки отказ
admin-service не отключает внешний канал: relay продолжает работать с cache. Если admin
недоступен до первой конфигурации, используются значения `.env`.

Порт relay на хост не публикуется. Сети `monitoring` и `app` нужны для двух webhook и
внутреннего config API; это не открывает доступ из браузера или извне.

**Это не внешний watchdog.** При отказе всего сервера, Docker, сети, Alertmanager или relay
сообщение с того же сервера отправить некому. Для такого случая нужна проверка
HTTPS/heartbeat с другой площадки. Внешний watchdog в Corvex пока не развёрнут и не принят.

## 2. Настройка суперадминистратором (основной способ)

Откройте **«Администрирование → Настройки → Алерты и доставка»**. Раздел виден только
пользователю с группой `SUPER_ADMIN`; делегированного `ADMIN_SERVER_CONFIG` без этой группы
недостаточно — backend возвращает 403.

В панели настраивается именно runtime policy:

- **Куда:** внутренние email-получатели и отправитель; независимый внешний SMTP;
  Telegram; MAX. Внешние каналы по умолчанию выключены.
- **Как часто:** отдельные интервалы повторов обычного и `critical` алерта, от 1 до
  43200 минут. Новый инцидент и `resolved` не ждут repeat interval.
- **При каких сбоях:** `ServiceDown`, `HighHttp5xxRate`, `MailQueueBacklog`,
  `DiskSpaceLow`, `JvmMemoryPressure`. Выключенная категория фильтруется обоими consumers.
  Будущие неизвестные категории по умолчанию не блокируются.

Нажмите **«Применить настройки алертов»**. Сохранение атомарно создаёт новую `revision` в
`admin_db.alert_runtime_config` и запись `ALERT_SETTINGS_CHANGED` в аудите. Ручное
редактирование YAML и перезапуск контейнеров не требуются.

Блок **«Фактическое применение»** опрашивает consumers:

- `APPLIED` — consumer загрузил revision, совпадающую с сохранённой;
- `PENDING` — consumer пока сообщает другую revision; обычно достаточно дождаться следующего
  10-секундного опроса;
- `UNAVAILABLE` — consumer или monitoring profile недоступен. Это не отображается как успех.

`APPLIED` подтверждает загрузку policy, а не доставку конкретного внешнего сообщения.
Фактическую доставку проверяйте отдельно по разделу 8.

## 3. Секреты и fallback через .env

SMTP password, Telegram token и MAX token — **write-only**. Public GET возвращает только
`configured` и `source` (`UI`, `ENVIRONMENT`, `NONE`), но не значение. Пустое поле при
сохранении означает «не менять секрет». Для UI-secret доступна операция «удалить и вернуться
к `.env`»; выключение канала выполняется отдельным переключателем.

Новые UI-секреты хранятся в `admin_db` только как AES-256-GCM ciphertext. Для них нужен
отдельный ключ:

```dotenv
CORVEX_ALERT_CONFIG_ENC_KEY=<Base64 из 32 случайных байт>
```

`scripts/first-run.ps1` генерирует ключ при новой установке и добавляет отсутствующий ключ в
существующий `.env`, не перезаписывая другие секреты. Не задавайте ключ в командной строке
или чате. Его потеря требует повторного ввода UI-секретов алертов. `.env` и ключ должны
входить в защищённый backup.

Последний effective config также кэшируется relay в `alert-relay-data`; backup/restore
сохраняет этот volume. Старые backup-наборы без него остаются совместимыми: после
восстановления relay заново получит config из `admin_db`.

Значения `.env` остаются поддерживаемым fallback и начальной конфигурацией до первого
сохранения из UI. После появления database revision несекретные значения берутся из UI;
секрет, не заменённый в UI, по-прежнему может использоваться из `.env`.

## 4. Что означает repeat interval

В `.env` задаются fallback-значения:

```dotenv
CORVEX_ALERT_REPEAT_INTERVAL=4h
CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL=30m
```

Допустимы duration `15m`, `1h`, `6h`, `1d`. В UI те же интервалы задаются обычным числом
минут. Alertmanager отправляет consumers минутный **delivery tick**; это не означает письмо
каждую минуту. Notification-service и relay самостоятельно подавляют повторы согласно
effective policy и делают это отдельно для каждого канала. При частичной ошибке следующий
tick повторяет только неудачный канал, не дублируя успешные.

Первое уведомление определяется правилом Prometheus `for`, `group_wait: 30s` и, для обновления
существующей группы, `group_interval: 5m`. `resolved` проходит без repeat-throttle и очищает
состояние инцидента. Grouping и silences остаются в Alertmanager.

## 5. Временное отключение уведомлений (silence)

Silence отключает **новые уведомления обоих каналов**, но не останавливает Prometheus,
Alertmanager или Grafana и не скрывает сам сбой. Состояние хранится в `alertmanager-data`.

```powershell
# Все Corvex-алерты на 60 минут.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 60

# Только один Prometheus instance на 2 часа.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 120 -Service chat-service:8088

# Effective revision/repeat/categories и активные silence.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status

# Досрочно завершить конкретный silence.
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 unsilence -SilenceId <uuid>
```

Длительность одной операции ограничена 30 сутками. Silence не отзывает уже отправленные
сообщения и не удаляет события, которые попали во внутреннюю очередь до его создания.

## 6. Каналы

### Внутренняя почта

Путь: `notification-service → RabbitMQ → SMTP`. Получатели вводятся через запятую. Этот канал
не является независимым от почтовой инфраструктуры, поэтому для сбоя самой почты нужен
внешний relay.

### Внешний SMTP

Используйте SMTP независимого провайдера или отдельный сервер, а не `mail-smtp-service`.
Незашифрованный SMTP намеренно не поддерживается: выберите `starttls` (обычно порт 587) или
`ssl` (обычно порт 465). Username можно оставить пустым только если провайдер разрешает relay
без логина; TLS обязателен в обоих случаях.

Env fallback (значения также можно ввести через UI):

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

### Telegram

Создайте отдельного bot через BotFather и добавьте его в нужный чат. Укажите chat/channel ID
(у групп он может быть отрицательным) и token через UI либо env fallback:

```dotenv
CORVEX_ALERT_TELEGRAM_ENABLED=true
CORVEX_ALERT_TELEGRAM_BOT_TOKEN=<token>
CORVEX_ALERT_TELEGRAM_CHAT_ID=<chat-id>
```

Telegram Bot API требует token в пути URL. Relay не пишет URL, тело ответа или token в лог.
HTTP-redirect для Telegram/MAX не выполняется, чтобы credentials и текст не ушли на другой адрес.

### MAX

Используется официальный endpoint `https://platform-api2.max.ru/messages`. Token передаётся
только в `Authorization`, не в query. Заполните ровно один `chat ID` или `user ID`:

```dotenv
CORVEX_ALERT_MAX_ENABLED=true
CORVEX_ALERT_MAX_BOT_TOKEN=<token>
CORVEX_ALERT_MAX_CHAT_ID=<chat-or-channel-id>
CORVEX_ALERT_MAX_USER_ID=
```

Если для TLS требуется цепочка Минцифры, поместите публичный PEM в отдельный `infra/alert-ca`
(или `CORVEX_ALERT_CA_DIR`) и укажите:

```dotenv
CORVEX_ALERT_EXTERNAL_CA_FILE=/etc/corvex/alert-ca/mincifry-chain.pem
```

Дополнительный CA добавляется к системным корням. Каталог монтируется read-only и не содержит
приватные ключи Corvex. Сообщение ограничено 3900 символами; MAX-отправки сериализуются с
интервалом не менее 0,55 с.

## 7. Применение инфраструктурного обновления

После обновления кода Corvex пересоберите изменённые образы и поднимите monitoring profile:

```powershell
docker compose up -d --build admin-service notification-service frontend
docker compose --profile monitoring up -d --build alert-relay alertmanager
```

Это нужно для установки **новой версии кода**, а не для каждого изменения настроек из UI.
Пароли, JWT и encryption keys в команды не передаются. Существующие volumes не удаляются.

## 8. Проверка каналов

Несколько алертов одного Alertmanager webhook объединяются в одно внешнее сообщение. Если
хотя бы один канал доставлен, relay не заставляет Alertmanager повторять весь webhook;
неудачный канал получает следующий delivery tick. Если все включённые каналы не доставлены,
HTTP 502 оставляет retry Alertmanager.

Для сквозной проверки внутреннего канала используйте controlled synthetic alert:

```powershell
py -3 scripts/alert_e2e.py --host mail.company.local --username admin@company.local --ca-file C:\corvex-pki\corvex-test-ca.crt --wait-seconds 180 --report artifacts\smoke\alert-e2e.json
```

Перед запуском он читает effective snapshot notification-service и проверяет, что внутренний
канал включён и `--username` входит в `internalRecipients`; старый `.env` здесь не используется.
Затем создаёт один ограниченный `CorvexAlertSmoke` и завершает его, не останавливая сервисы.
JSON PASS доказывает **внутреннюю IMAP-цепочку**. Для внешнего канала отдельно подтвердите
фактическое сообщение на внешней почте, в Telegram или MAX и отсутствие sanitised
`delivery failed` в relay. Не останавливайте production-сервис ради alert-test.

## 9. Диагностика без секретов

```powershell
powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
docker compose logs --tail 100 alertmanager alert-relay notification-service admin-service
```

`scripts/alerts.ps1 status` читает effective relay revision, repeat и disabled categories, а
не устаревший `.env`. `refresh=FAILED` означает, что последний config refresh не удался;
прежний валидный cache продолжает работать.

Не публикуйте `.env`, `docker compose config` с environment, SMTP password, bot token или
полные внешние HTTP-запросы. Для отчёта достаточно имени канала, revision, HTTP-статуса и
sanitised ошибки.
