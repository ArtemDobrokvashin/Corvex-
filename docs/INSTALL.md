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

```powershell
git clone https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex
git switch main
git pull --ff-only
```

Если git на сервере нежелателен, скачайте ZIP ветки `main` и распакуйте в `C:\Corvex`.

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

| Переменная | Значение |
|---|---|
| `CORVEX_DOMAIN` | веб-имя, например `mail.company.local` |
| `CORVEX_WEB_ORIGINS` | обычно пусто: Compose сам подставит `https://$CORVEX_DOMAIN`; заполнять только для дополнительных origin через запятую |
| `CORVEX_MAIL_DOMAIN` | почтовый домен адресов, например `company.local` |
| `CORVEX_INTERNAL_API_TOKEN` | секрет внутренних API; генерируется скриптом, менять обязательно, если осталось `change-me...` |
| `CORVEX_SMTP_ENABLED`, `CORVEX_IMAP_ENABLED`, `CORVEX_POP3_ENABLED` | `false` до приёмки соответствующего контура |
| `CORVEX_ALERT_RECIPIENTS` | получатели внутреннего email-канала алертов |
| `CORVEX_BOOTSTRAP_ADMIN_*` | логин, пароль и email первого суперадминистратора |
| `CORVEX_LDAP_URL`, `CORVEX_LDAP_BASE` | заполнить при наличии Active Directory; иначе логины локальные |

Пароль внешних ящиков и секреты каналов алертов шифруются ключами
`CORVEX_EXTERNAL_MAIL_ENC_KEY` и `CORVEX_ALERT_CONFIG_ENC_KEY`. Потеря ключа означает
повторный ввод этих секретов, поэтому `.env` входит в резервную копию.

## 6. TLS-сертификат

Самоподписанный сертификат из шага 4 годится только для внутреннего пилота.

- **Публичный домен:** Let's Encrypt (`win-acme` на Windows, certbot на Linux). Файлы:
  `infra\certs\fullchain.pem` (вся цепочка) и `infra\certs\privkey.pem`.
- **Внутренний домен:** сертификат корпоративного УЦ на `mail.<домен>`; корневой CA должен
  быть в доверенных на машинах пользователей.

Имя в сертификате — `mail.<ваш-домен>`: оно обслуживает веб, IMAP и POP3. В
`infra\nginx\conf.d\corvex.conf` поправьте `server_name` на то же имя.

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

DKIM-ключ генерируется при первом запуске, публичный ключ показывается в админке. PTR
(обратную зону) заказывают у провайдера: `IP → mail.company.ru`.

## 8. Первый запуск

```powershell
docker compose up -d --build
docker compose ps
```

Первая сборка 12 Java-сервисов и фронтенда занимает 10–30 минут. Ждите `healthy` у
сервисов с healthcheck; у `frontend`, `nginx` и `spamd` его нет, поэтому у них будет
`Up`. Первый старт ClamAV скачивает базы сигнатур — до 5 минут это норма.

```powershell
docker compose logs --tail 100 <сервис>      # если что-то в restarting
docker compose --profile monitoring up -d    # мониторинг отдельным профилем
```

Grafana доступна на `https://mail.<домен>/grafana/`; доступ ограничен allowlist-подсетью
в `infra\nginx\conf.d\corvex.conf` — поправьте её под свою сеть.

## 9. Проверки после запуска

1. **Вход.** `https://mail.<домен>` → экран входа. Логин — `CORVEX_BOOTSTRAP_ADMIN_USERNAME`
   (по умолчанию `admin`); пароль — из `.env`, а если он пуст, сгенерированный пароль
   печатается один раз: `docker compose logs auth-service | Select-String BOOTSTRAP`.
   Интерфейс попросит сменить пароль.
2. **Gateway жив.** `curl.exe -k https://mail.<домен>/api/v1/auth/me` → `401`
   (маршрут существует и требует токен).
3. **JWKS.** `docker compose exec web-gateway wget -qO- http://auth-service:8081/oauth2/jwks`
   → JSON с публичным ключом.
4. **Миграции.** `docker compose logs auth-service | Select-String Flyway` и то же для
   `admin-service`, `chat-service`, `mail-storage-service` — строки об успешных миграциях.
5. **SMTP выключен по умолчанию.** В логах `mail-smtp-service` есть
   `SMTP listener disabled`. Это ожидаемое состояние, а не ошибка.
6. **Календарь.** Создайте событие с напоминанием «за 5 минут», обновите страницу —
   событие сохранилось. Затем в `docker compose logs -f notification-service`
   дождитесь строки об отправке: письмо приходит на вашу почту Corvex.
7. **Алерты.** Не останавливайте рабочий сервис: используйте `scripts/alert_e2e.py`,
   он создаёт один синтетический алерт и завершает его.

Полный набор проверок, включая приёмку почты и почтовых клиентов — [`TESTING.md`](TESTING.md).

## 10. Обновление и откат

```powershell
git pull
docker compose up -d --build          # миграции Flyway применит сам
docker compose up -d --build chat-service   # пересборка одного сервиса
```

Откат: вернуть предыдущий коммит (или теги образов) и повторить `up -d`. Миграции только
аддитивные, поэтому схема откат переживает. Полный порядок с проверками —
[`OPERATIONS.md`](OPERATIONS.md).

## 11. Резервное копирование

```powershell
.\scripts\backup.ps1 -BackupRoot D:\CorvexBackup
```

Скрипт сохраняет дампы четырёх баз PostgreSQL, архивы томов (письма, вложения, файлы),
`.env`, сертификаты, ключ JWT и `manifest.json` с SHA-256 всех файлов. Индекс
Elasticsearch не входит — он перестраивается. Копии должны лежать **на другом носителе**.

Проверка восстановления и расписание — [`OPERATIONS.md`](OPERATIONS.md).

## 12. Чек-лист перед доступом извне

- [ ] В `.env` не осталось ни одного `change-me...`.
- [ ] TLS — настоящий сертификат, не пилотный самоподписанный.
- [ ] В nginx поправлены `server_name` и подсеть доступа к `/grafana/`.
- [ ] Алерты приходят на внешний ящик.
- [ ] Одна копия бэкапа лежит вне сервера, восстановление проверено.
- [ ] `CORVEX_SMTP_ENABLED=false`, пока почтовый контур не принят; MX не опубликован.

## Проблемы и решения

| Симптом | Что проверить |
|---|---|
| Сборка Maven падает на сервисе | `docker compose build <сервис>` без `-d`, читать первую ошибку компиляции |
| Контейнер `unhealthy`/`restarting` | `docker compose logs --tail 100 <сервис>`; чаще всего пароль из `.env` не совпал с уже инициализированным томом или мало памяти |
| nginx: `cannot load certificate` | нет файлов в `infra\certs` — выполните шаг 4 или 6 |
| ClamAV долго стартует | первый запуск скачивает ~200 МБ сигнатур; без интернета базы приносятся вручную в том `clamav-data` |
| Elasticsearch перезапускается | нужно ≥2,5 ГБ RAM; в Linux-VM `sudo sysctl -w vm.max_map_count=262144` и в `/etc/sysctl.conf` |
| Видна жёлтая плашка «демо» | вы вошли кнопкой демо или бэкенд недоступен: проверьте `docker compose ps` и логи `auth-service` |
| Внешние письма не приходят | SMTP выключен осознанно; после приёмки — порты 25 извне, MX, PTR, mail-tester ≥ 9/10 |

Остальные сбои, включая почтовые и эксплуатационные — [`OPERATIONS.md`](OPERATIONS.md).

## Приложение: first-run для Linux

```bash
mkdir -p infra/secrets infra/certs
[ -f infra/secrets/jwt_private.pem ] || docker run --rm -v "$PWD/infra/secrets:/out" alpine sh -c \
  "apk add --no-cache openssl >/dev/null && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /out/jwt_private.pem"
[ -f infra/certs/fullchain.pem ] || docker run --rm -v "$PWD/infra/certs:/out" alpine sh -c \
  "apk add --no-cache openssl >/dev/null && openssl req -x509 -newkey rsa:2048 -keyout /out/privkey.pem -out /out/fullchain.pem -days 90 -nodes -subj '/CN=mail.company.local'"

while IFS= read -r line; do
  case "$line" in
    *=change-me-strong-password) echo "${line%%=*}=$(openssl rand -hex 24)" ;;
    CORVEX_TOTP_ENC_KEY=*)          echo "CORVEX_TOTP_ENC_KEY=$(openssl rand -base64 32)" ;;
    CORVEX_EXTERNAL_MAIL_ENC_KEY=*) echo "CORVEX_EXTERNAL_MAIL_ENC_KEY=$(openssl rand -base64 32)" ;;
    CORVEX_ALERT_CONFIG_ENC_KEY=*)  echo "CORVEX_ALERT_CONFIG_ENC_KEY=$(openssl rand -base64 32)" ;;
    *) echo "$line" ;;
  esac
done < .env.example > .env
```

Дальше — шаги 5–12, команды Docker идентичны.
