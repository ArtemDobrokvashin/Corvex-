# Установка и первый запуск

Инструкция для администратора: от чистого сервера до рабочего веб-интерфейса. Команды
приведены для PowerShell; внутри Linux-VM они те же, отличается только установка Docker.

Перед началом:

- требования к железу, сети и DNS — [`SERVER-REQUIREMENTS.md`](SERVER-REQUIREMENTS.md);
- что уже проверено, а что нет — [`STATUS.md`](STATUS.md);
- **не направляйте боевые MX-записи на сервер**, пока почтовый контур не принят.

## 0. Что понадобится

- [ ] Сервер: Windows Server 2019/2022 или Ubuntu Server 24.04 LTS. Для 50 ящиков —
      4–6 vCPU, 16 ГБ RAM, 200 ГБ SSD. Для знакомства достаточно рабочей станции
      Windows 10/11 с 16 ГБ RAM и 60–80 ГБ свободного места.
- [ ] Имя для веб-интерфейса (`mail.company.local`) и почтовый домен (`company.local`).
- [ ] Для внешней почты: публичный IP, порты 25 (в обе стороны), 80, 443, 587, 993/143,
      995/110 и возможность поставить PTR.
- [ ] Право администратора на сервере и доступ к DNS-зоне.
- [ ] Отдельный внешний ящик для алертов — не на этом сервере.

## 1. Сеть

Наружу открываются только порты из таблицы; базы, Redis, RabbitMQ и Elasticsearch портов
на хост не публикуют.

| Порт | Назначение |
|---|---|
| 443 | веб-интерфейс, API, WebSocket чата |
| 80 | редирект на HTTPS, выпуск Let's Encrypt |
| 25 | приём почты от внешних серверов |
| 587 | отправка из почтовых клиентов (SMTP AUTH) |
| 993, 143 | IMAP |
| 995, 110 | POP3 |

```powershell
443,80,25,587,993,143,995,110 | ForEach-Object {
  New-NetFirewallRule -DisplayName "Corvex $_" -Direction Inbound -Protocol TCP -LocalPort $_ -Action Allow
}
```

### Сеть Linux-VM в Hyper-V

Порт 25 должен принимать соединения из интернета **напрямую в VM**, поэтому выбора
всего два, и оба надо решить до установки.

**Вариант 1 — внешний виртуальный коммутатор (предпочтительно).** VM получает адрес
в той же сети, что и хост, и публичный IP приходит на неё. Правила брандмауэра
создаются для IP VM, как показано выше.

**Вариант 2 — NAT и portproxy.** Нужен, если провайдер отдаёт один публичный IP,
привязанный к MAC-адресу физической карты, и второй адрес получить нельзя. Тогда
VM живёт за NAT, а хост пробрасывает порты:

```powershell
New-NetNat -Name CorvexNat -InternalIPInterfaceAddressPrefix 192.168.77.0/24
# IP VM — из Hyper-V Manager; он должен быть статическим внутри VM
25,80,443,587,993,143,995,110 | ForEach-Object {
  netsh interface portproxy add v4tov4 listenport=$_ listenaddress=0.0.0.0 `
    connectport=$_ connectaddress=192.168.77.10
}
Get-NetNat; netsh interface portproxy show v4tov4
```

Без `portproxy` веб и почта снаружи не работают, хотя внутри VM всё зелёное. Проверять
надо **снаружи**: `Test-NetConnection <публичный-IP> -Port 25` с другой машины.

### Автозапуск и время

Хост перезагрузится после обновления Windows — VM и Docker сами не поднимутся.

1. Hyper-V Manager → свойства VM → **Automatic Start Action: Start if running**,
   **Startup Delay: 60 секунд**.
2. Внутри VM Docker запускается сам (`systemctl enable --now docker`), а
   `restart: unless-stopped` в Compose поднимает контейнеры.
3. Внутри VM включите синхронизацию времени: `sudo timedatectl set-ntp true` и
   проверьте `timedatectl status`. Расхождение часов ломает TLS, JWT и DKIM-подписи.

## 2. Docker

**Вариант A — Windows Server: Linux-VM в Hyper-V (рекомендуется).** Docker Desktop
официально не поддерживается на Windows Server, а Linux-контейнеры нативно там не
работают.

1. Включите Hyper-V: `Install-WindowsFeature Hyper-V -IncludeManagementTools -Restart`.
2. Создайте VM: Ubuntu Server 24.04, поколение 2, vCPU = все минус 2, RAM = вся минус
   4 ГБ, диск на SSD (100 ГБ система + 100 ГБ данные), сетевой адаптер — на **внешний**
   виртуальный коммутатор.
3. Внутри VM:

```bash
sudo apt update && sudo apt install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # перелогиниться
docker --version && docker compose version
```

**Вариант B — пилот на Windows 10/11: Docker Desktop** с WSL2-бэкендом. В Settings →
Resources отдайте минимум 6 CPU и 12 ГБ RAM.

**Вариант C — чистый Linux-сервер.** То же, что вариант A, шаг 3, без Hyper-V. Самый
простой в эксплуатации путь.

## 3. Получение исходного кода

**Где должен лежать каталог.** Там, где его видят и вы, и Docker-демон. Compose
монтирует `./infra/secrets`, `./infra/certs` и `./infra/nginx` с той файловой системы,
которую видит демон, а не с той, где вы набрали команду.

| Как ставите | Где клонировать |
|---|---|
| Вариант A (Hyper-V) и C (чистый Linux) | **внутри VM**, например `/opt/corvex` |
| Вариант B (Docker Desktop) | на хосте, например `C:\Corvex`: Desktop пробрасывает каталог в WSL2 |

Если клонировать в `C:\Corvex`, а Docker работает в отдельной VM, контейнеры не увидят
`infra/secrets`: монтирование молча создаст пустые каталоги, и nginx упадёт на
`cannot load certificate`. Признак — файлы в репозитории есть, а внутри контейнера их нет.

```bash
# внутри Linux-VM
sudo mkdir -p /opt/corvex && sudo chown "$USER" /opt/corvex
git clone https://github.com/ArtemDobrokvashin/Corvex-.git /opt/corvex
cd /opt/corvex
git switch main
git pull --ff-only
```

```powershell
# на Windows-хосте при варианте B
git clone https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex
git switch main
git pull --ff-only
```

Если git на сервере нежелателен, скачайте ZIP ветки `main` и распакуйте в тот же каталог.

## 4. Первичная инициализация

Не копируйте `.env.example` вручную: скрипт создаст `.env` с уникальным случайным
секретом на каждую переменную, ключи подписи JWT и DKIM, а также самоподписанный
сертификат на 90 дней для пилота. Есть две версии скрипта, и они дают одинаковый
результат.

**Внутри Linux-VM** (Docker не нужен, используется системный `openssl`):

```bash
./scripts/first-run.sh
# либо сразу с доменами, чтобы не править .env руками:
./scripts/first-run.sh --domain mail.company.ru --mail-domain company.ru
```

**На Windows-хосте** (PowerShell 5.1+):

```powershell
Set-ExecutionPolicy -Scope Process Bypass   # если политика запрещает скрипты
.\scripts\first-run.ps1
```

`.env` создаётся в кодировке **UTF-8 без BOM** с переводами строк LF — так его читает
`docker compose`. Если файл правили вручную в Windows-редакторе, проверьте это
(`scripts/preflight.py` сообщит о BOM отдельной строкой).

Ожидаемый результат в обоих случаях: строки `[OK]` и файлы
`infra/secrets/jwt_private.pem`, `infra/secrets/dkim_private.pem`,
`infra/certs/fullchain.pem` и `.env` в корне. Скрипт печатает готовую DNS-строку
DKIM — её вносят в зону на шаге 7.

Повторный запуск безопасен: существующие ключи, сертификат и `.env` не перезаписываются.
Скрипт только дописывает отсутствующие переменные и заменяет оставшиеся заглушки.

> Если в `.env` остались значения `change-me-strong-password` или
> `base64-32-byte-key-for-aes-gcm`, приложение вернёт `503` на TOTP, внешние ящики и
> секреты алертов. Удалите `.env` и запустите скрипт заново **до** первого
> `docker compose up`: пароли баз применяются при создании томов.

> Пароли из `.env` применяются к базам **при первом создании томов**. После первого
> запуска менять `POSTGRES_PASSWORD`/`REDIS_PASSWORD` бессмысленно — их придётся менять и
> внутри сервисов. На пустом стенде можно начать заново: `docker compose down -v`.

## 5. Настройка `.env`

`first-run.sh` умеет заполнить три главные переменные сам:

```bash
./scripts/first-run.sh --domain mail.company.ru --mail-domain company.ru \
                       --public-base-url https://mail.company.ru
```

Остальное правится вручную — файл один и тот же на Windows и в Linux-VM:

| Переменная | Значение |
|---|---|
| `CORVEX_DOMAIN` | веб-имя, например `mail.company.ru` |
| `CORVEX_WEB_ORIGINS` | обычно пусто: Compose сам подставит `https://$CORVEX_DOMAIN`; заполнять только для дополнительных origin через запятую |
| `CORVEX_MAIL_DOMAIN` | почтовый домен адресов, например `company.ru` |
| `CORVEX_PUBLIC_BASE_URL` | внешний адрес, на который провайдер вернёт браузер после согласия OAuth |
| `CORVEX_INTERNAL_API_TOKEN` | секрет внутренних API; генерируется скриптом, менять обязательно, если осталось `change-me...` |
| `CORVEX_SMTP_ENABLED`, `CORVEX_IMAP_ENABLED`, `CORVEX_POP3_ENABLED` | `false` до приёмки соответствующего контура |
| `CORVEX_ALERT_RECIPIENTS` | получатели внутреннего email-канала алертов |
| `CORVEX_BOOTSTRAP_ADMIN_*` | логин, пароль и email первого суперадминистратора |
| `CORVEX_LDAP_URL`, `CORVEX_LDAP_BASE` | заполнить при наличии Active Directory; иначе логины локальные |

Пароль внешних ящиков и секреты каналов алертов шифруются ключами
`CORVEX_EXTERNAL_MAIL_ENC_KEY` и `CORVEX_ALERT_CONFIG_ENC_KEY`. Потеря ключа означает
повторный ввод этих секретов, поэтому `.env` входит в резервную копию.

Проверка перед первым запуском (в Windows PowerShell или в bash):

```bash
grep -nE '=change-me-strong-password$|=base64-32-byte-key-for-aes-gcm$' .env || echo "[OK] заглушек нет"
```

```powershell
Select-String -Path .env -Pattern '=change-me-strong-password$|=base64-32-byte-key-for-aes-gcm$'
# пусто = заглушек нет
```

Если вывод не пуст, приложение вернёт `503` на TOTP, внешние ящики и секреты алертов.
Удалите `.env` и запустите script из шага 4 заново — **до** первого `docker compose up`.

## 6. TLS-сертификат

Самоподписанный сертификат из шага 4 годится только для внутреннего пилота.

- **Публичный домен:** Let's Encrypt. На Linux — `certbot`, на Windows-хосте — `win-acme`.
  Нужны файлы `infra/certs/fullchain.pem` (вся цепочка) и `infra/certs/privkey.pem`.
- **Внутренний домен:** сертификат корпоративного УЦ на `mail.<домен>`; корневой CA должен
  быть в доверенных на машинах пользователей.

Внутри Linux-VM проще всего получить сертификат на хосте и скопировать файлы:

```bash
sudo certbot certonly --standalone -d mail.company.ru
sudo cp /etc/letsencrypt/live/mail.company.ru/fullchain.pem infra/certs/
sudo cp /etc/letsencrypt/live/mail.company.ru/privkey.pem   infra/certs/
sudo chown 100:101 infra/certs/privkey.pem && sudo chmod 600 infra/certs/privkey.pem
```

Имя в сертификате — `mail.<ваш-домен>`: оно обслуживает веб, IMAP и POP3. В файле
`infra/nginx/conf.d/corvex.conf` поправьте `server_name` на то же имя:

```bash
sed -i 's/server_name .*/server_name mail.company.ru;/' infra/nginx/conf.d/corvex.conf
```

На Windows-хосте пути те же, но с обратными слэшами: `infra\nginx\conf.d\corvex.conf`.
Файлы внутри VM — всегда через прямой слэш, даже если каталог смонтирован с Windows.

## 7. DNS

Для веб-only пилота достаточно A-записи. Полный набор для почты:

```dns
mail.company.ru.              A      203.0.113.10
company.ru.                   MX 10  mail.company.ru.
company.ru.                   TXT    "v=spf1 mx ~all"          ; на пилоте, в проде -all
corvex1._domainkey.company.ru TXT    "v=DKIM1; k=rsa; p=<публичный ключ>"
_dmarc.company.ru.            TXT    "v=DMARC1; p=none; rua=mailto:dmarc@company.ru"
autoconfig.company.ru.        CNAME  mail.company.ru.
_imaps._tcp.company.ru.       SRV 0 1 993 mail.company.ru.
_submission._tcp.company.ru.  SRV 0 1 587 mail.company.ru.
```

Публичный ключ DKIM скрипт из шага 4 печатает готовой TXT-строкой; тот же ключ лежит в
`infra/secrets/dkim_public.pem` и показывается в админке. PTR (обратную зону) заказывают
у провайдера: `IP → mail.company.ru`.

## 8. Первый запуск

### 8.0. Проверка окружения

Перед первой сборкой прогоните предполётную проверку — она только читает и ничего не
создаёт:

```bash
python3 scripts/preflight.py            # в Linux-VM
python3 scripts/preflight.py --strict   # WARN тоже считается провалом
```

Запускать его надо **там, где работает Docker**: внутри VM при варианте A, на хосте при
Docker Desktop. Проверки на Windows-хосте при варианте A покажут не ту машину.

Скрипт отвечает на те вопросы, из-за которых установка обычно и встаёт: запущен ли
Docker и какой у него движок, хватает ли vCPU/RAM/диска, свободны ли порты 80, 443, 25,
587, 143, 993, 995, 8080, разбирается ли `.env`, на месте ли ключи и сертификаты,
достаточен ли `vm.max_map_count` для Elasticsearch. Каждая строка — `PASS`, `WARN` или
`FAIL` с подсказкой. Коды возврата: `0` — провалов нет, `1` — есть `FAIL`, `2` — `--strict`
при наличии `WARN`.

Типичный `FAIL` новичка — `BOM` в `.env`: файл, сохранённый в Windows PowerShell 5.1
командой `Set-Content -Encoding UTF8`, начинается с невидимого символа, и `docker compose`
падает на разборе. `first-run.ps1` больше так не делает; если `.env` правили руками,
сохраняйте его как **UTF-8 без BOM** (в блокноте — «UTF-8», не «UTF-8 с BOM»).

### 8.1. Запуск

Команды одинаковые на Windows и в Linux-VM:

```bash
docker compose up -d --build
docker compose ps
```

Первая сборка 12 Java-сервисов и фронтенда занимает 10–30 минут. Ждите `healthy` у
сервисов с healthcheck; у `frontend`, `nginx` и `spamd` его нет, поэтому у них будет
`Up`. Первый старт ClamAV скачивает базы сигнатур — до 5 минут это норма.

```bash
docker compose logs --tail 100 <сервис>      # если что-то в restarting
docker compose --profile monitoring up -d    # мониторинг отдельным профилем
```

Grafana доступна на `https://mail.<домен>/grafana/`; доступ ограничен allowlist-подсетью
в `infra/nginx/conf.d/corvex.conf` — поправьте её под свою сеть.

> Внутри Linux-VM `sysctl` нужен для Elasticsearch. Если он перезапускается:
> `sudo sysctl -w vm.max_map_count=262144` и та же строка в `/etc/sysctl.conf`.

## 9. Проверки после запуска

1. **Вход.** `https://mail.<домен>` → экран входа. Логин — `CORVEX_BOOTSTRAP_ADMIN_USERNAME`
   (по умолчанию `admin`); пароль — из `.env`, а если он пуст, сгенерированный пароль
   печатается один раз:

   ```bash
   docker compose logs auth-service | grep BOOTSTRAP          # Linux-VM
   ```

   ```powershell
   docker compose logs auth-service | Select-String BOOTSTRAP # Windows-хост
   ```

   Интерфейс попросит сменить пароль. Временный пароль вводить повторно не нужно: сессия
   уже выдана по нему, и смена доступна только пока аккаунт в состоянии «смените пароль».
   В настройках безопасности текущий пароль по-прежнему обязателен — иначе украденный
   access-токен позволял бы забрать учётную запись.

   > Если открываете стенд по адресу, отличному от `CORVEX_DOMAIN` (например,
   > `https://localhost` на чистой установке или `https://192.168.x.x` с телефона),
   > это отдельный origin. Шлюз принимает его как same-origin автоматически
   > (`SameOriginFilter`); для остальных чужих origin нужен `CORVEX_WEB_ORIGINS`.
   > До этой правки вход с `https://localhost` отвечал `403` без текста.

2. **Gateway жив.** `curl -k https://mail.<домен>/api/v1/auth/me` → `401`
   (маршрут существует и требует токен). На Windows вместо `curl` — `curl.exe`.
3. **JWKS.**
   `docker compose exec web-gateway wget -qO- http://auth-service:8081/oauth2/jwks`
   → JSON с публичным ключом.
4. **Миграции.** Строки Flyway в `auth-service`, `admin-service`, `chat-service`,
   `mail-storage-service`: `docker compose logs <сервис> | grep Flyway` (Linux) или
   `| Select-String Flyway` (Windows). У каждого сервиса своя история версий, поэтому
   сверяйте с последней миграцией в `services/<сервис>/src/main/resources/db/migration`,
   а не с общим номером: `V1…V14` в одном сервисе не значит «14 миграций везде».
   Согласованность проверяет `scripts/docs_audit.py`.
5. **Секреты не заглушки.** Шаг 5: в `.env` нет `change-me…` и
   `base64-32-byte-key-for-aes-gcm`.
6. **SMTP выключен по умолчанию.** В логах `mail-smtp-service` есть
   `SMTP listener disabled`. Это ожидаемое состояние, а не ошибка.
7. **Календарь.** Создайте событие через «Все параметры» с напоминанием «за 5 минут»
   и обновите страницу — событие должно остаться в списке. Если оно исчезло, сервер
   его не принял: виджет показывает ошибку сохранения, но не прячет событие молча.

   **Серверное напоминание по почте требует включённого SMTP.** По умолчанию
   `CORVEX_SMTP_ENABLED=false`, приём и отправка почты выключены (шаг 6), поэтому
   письма от `notification-service` физически некуда доставить. Для полной проверки:
   задайте `CORVEX_SMTP_ENABLED=true` в `.env`, пересоздайте `mail-smtp-service` и
   `notification-service`, затем создайте **новое** событие — и в
   `docker compose logs -f notification-service` дождитесь строки об отправке.

   Уже созданное напоминание повторять не нужно и нельзя: запись со статусом `FAILED`
   не возвращается в очередь (уникальный ключ «событие + момент»), поэтому после
   исправления настроек нужен новый слот напоминания. Статус доставки виден в
   `mail_db`:

   ```powershell
   docker compose exec -T postgres psql -U corvex -d mail_db -c "select status, attempts, last_error from calendar_reminder_deliveries order by created_at desc limit 3;"
   ```
8. **Алерты.** Не останавливайте рабочий сервис: используйте `scripts/alert_e2e.py`,
   он создаёт один синтетический алерт и завершает его.

   Скрипт проверяет цепочку `alertmanager → alert-relay → notification-service → SMTP →
   IMAP`, поэтому у него есть четыре предпосылки. Без них он честно остановится на
   первой же, и это не поломка стенда:

   1. **Профиль `monitoring` поднят** (по умолчанию он выключен):
      `docker compose --profile monitoring up -d`.
   2. **Внутренний email-канал включён** и получатель указан: *Админ → Настройки →
      Алерты*. Скрипт читает фактический runtime status, поэтому перезапуск не нужен.
   3. **IMAP включён**: `CORVEX_IMAP_ENABLED=true` в `.env`, затем пересоздать
      `mail-imap-service`. Порт 993 должен отвечать: `Test-NetConnection <домен> -Port 993`.
   4. **Пароль приложения**, а не пароль входа: *Безопасность → Пароли приложений →
      Создать*. Он показывается один раз; в консоли вводится скрыто либо передаётся
      переменной `CORVEX_TEST_ALERT_PASSWORD`.

   ```bash
   python3 scripts/alert_e2e.py --username admin@company.local --host mail.<домен> \
     --ca-file infra/certs/fullchain.pem
   ```

   ```powershell
   py scripts\alert_e2e.py --username admin@company.local --host mail.company.local `
     --ca-file infra\certs\fullchain.pem
   ```

   **Если стенд поднят с другим именем проекта** (`docker compose -p corvex-fresh …`),
   передайте его скрипту: `--project corvex-fresh` или переменной
   `COMPOSE_PROJECT_NAME`. Иначе compose ищет контейнеры проекта по имени каталога и
   отвечает `service "alert-relay" is not running`, хотя сервис работает.

Полный набор проверок, включая приёмку почты и почтовых клиентов — [`TESTING.md`](TESTING.md).

Отдельно — проверка устойчивости к перезапуску хранилища: раньше рестарт
`mail-storage-service` отправлял письма в DLQ, откуда они не возвращаются.

```bash
./scripts/verify-dlq-resilience.sh --baseline   # текущие счётчики очередей
./scripts/verify-dlq-resilience.sh              # полный тест с перезапуском
```

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify-dlq-resilience.ps1 -BaselineOnly
```

Скрипт ждёт `healthy`, ждёт опустошения очередей и печатает `[PASS]` или `[FAIL]` по
росту DLQ. Очереди не очищает. Коды возврата: `1` — DLQ вырос, `2` — сервис не поднялся.

## 10. Обновление и откат

```bash
git pull
python3 scripts/preflight.py                # новые обязательные ключи .env видны здесь
docker compose up -d --build                # миграции Flyway применит сам
docker compose up -d --build chat-service   # пересборка одного сервиса
```

После `git pull` в `.env` может появиться новая обязательная переменная: compose объявляет
её как `${VAR:?…}`, и без значения не стартует. `preflight.py` называет такие ключи
поимённо до сборки, а не после.

Откат: вернуть предыдущий коммит (или теги образов) и повторить `up -d`. Миграции только
аддитивные, поэтому схема откат переживает. Полный порядок с проверками —
[`OPERATIONS.md`](OPERATIONS.md).

## 11. Резервное копирование

**Windows-хост:**

```powershell
.\scripts\backup.ps1 -BackupRoot D:\CorvexBackup
```

Скрипт сохраняет дампы четырёх баз PostgreSQL, архивы томов (письма, вложения, файлы),
`.env`, сертификаты, ключ JWT и `manifest.json` с SHA-256 всех файлов. Индекс
Elasticsearch не входит — он перестраивается. Копии должны лежать **на другом носителе**.

**Linux-VM: скриптов пока нет.** `backup.ps1`, `restore.ps1` и `restore_verify.ps1`
написаны на PowerShell. Внутри Linux-VM их запустить нельзя, а bash-версий ещё не
написано. Это известный пробел: приёмка P3 (восстановление из копии) закрыта только для
Windows-хоста. Пока сервер на Windows, копию удобнее делать с хоста, обращаясь к томам
VM через общий каталог, либо установить в VM PowerShell 7 и запускать те же скрипты.
Честно: **непроверенный сценарий копирования на Linux не считается рабочим.**

Проверка восстановления и расписание — [`OPERATIONS.md`](OPERATIONS.md).

## 12. Чек-лист перед доступом извне

- [ ] В `.env` не осталось ни одного `change-me...` и ни одной заглушки `base64-32-byte-key-for-aes-gcm`.
- [ ] TLS — настоящий сертификат, не пилотный самоподписанный.
- [ ] В nginx поправлены `server_name` и подсеть доступа к `/grafana/`.
- [ ] Порты 25, 80, 443, 587 проверены **снаружи** (`Test-NetConnection <IP> -Port 25`).
- [ ] Алерты приходят на внешний ящик.
- [ ] Одна копия бэкапа лежит вне сервера, восстановление проверено.
- [ ] Если подключены внешние ящики Google: известно, что в статусе Testing вход
      придётся повторять каждые 7 дней (`reauthRequired`), и дата пилота это учитывает.
- [ ] `CORVEX_SMTP_ENABLED=false`, пока почтовый контур не принят; MX не опубликован.

## Проблемы и решения

| Симптом | Что проверить |
|---|---|
| Сборка Maven падает на сервисе | `docker compose build <сервис>` без `-d`, читать первую ошибку компиляции |
| Контейнер `unhealthy`/`restarting` | `docker compose logs --tail 100 <сервис>`; чаще всего пароль из `.env` не совпал с уже инициализированным томом или мало памяти |
| `503` на TOTP, внешних ящиках или секретах алертов | в `.env` остались заглушки ключей AES-GCM — см. шаг 5 |
| nginx: `cannot load certificate` | нет файлов в `infra/certs` — выполните шаг 4 или 6 |
| Снаружи не открывается веб или почта, внутри всё работает | VM за NAT: нужен `portproxy` (шаг 1, вариант 2) либо внешний виртуальный коммутатор |
| ClamAV долго стартует | первый запуск скачивает ~200 МБ сигнатур; без интернета базы приносятся вручную в том `clamav-data` |
| Elasticsearch перезапускается | нужно ≥2,5 ГБ RAM; в Linux-VM `sudo sysctl -w vm.max_map_count=262144` и в `/etc/sysctl.conf` |
| `docker compose` падает: `unexpected character "\ufeff" in variable name` | `.env` сохранён с BOM. Пересохраните как UTF-8 без BOM или удалите `.env` и запустите `first-run` заново |
| Видна жёлтая плашка «демо» | вы вошли кнопкой демо или бэкенд недоступен: проверьте `docker compose ps` и логи `auth-service` |
| Внешние письма не приходят | SMTP выключен осознанно; после приёмки — порты 25 извне, MX, PTR, mail-tester ≥ 9/10 |

Остальные сбои, включая почтовые и эксплуатационные — [`OPERATIONS.md`](OPERATIONS.md).
