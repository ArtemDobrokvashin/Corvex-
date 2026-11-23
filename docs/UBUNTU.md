# Ubuntu: от чистого сервера до работающего Corvex

Пошагово, с одной командой на шаг. Для Ubuntu Server 22.04/24.04 LTS. Всё, что
делается без домена, отмечено отдельно: домен нужен только для внешней почты и
настоящего TLS, остальное проверяется и работает без него.

Правило прежнего дня: **один шаг — одна команда, ждём вывод.** Секреты в чат не
присылаются.

---

## 0. Что должно быть до начала

| Что | Проверка |
|---|---|
| Ubuntu 22.04 или 24.04 | `lsb_release -a` |
| 4 vCPU, 16 ГиБ RAM, 100 ГиБ свободного диска | `nproc; free -g; df -h /` |
| Доступ по SSH, ключ или пароль | `ssh <пользователь>@<IP>` |
| Права sudo | `sudo -v` |

Если сервер только выдали и неясно, подходит ли он, — сначала проверка **снаружи**,
до копирования репозитория:

```bash
python3 scripts/server_readiness.py --host <IP> --ssh-user <пользователь> --public-ip <IP>
```

Она ничего не меняет и отвечает на три вопроса: есть ли доступ, хватает ли ресурсов,
не заняты ли 80/443/25/587/993/995 и не открыты ли наружу базы.

---

## 1. Подготовка хоста — одна команда

```bash
git clone https://github.com/ArtemDobrokvashin/Corvex-.git /srv/corvex
cd /srv/corvex
sudo ./scripts/prepare-ubuntu.sh --check
```

`--check` ничего не меняет: показывает, чего не хватает. Затем — сама подготовка:

```bash
sudo ./scripts/prepare-ubuntu.sh --user $USER
```

Что делает (и почему именно это, а не больше):

1. ставит `ca-certificates curl git openssl python3` — минимум для `first-run.sh`;
2. ставит Docker Engine и `docker-compose-plugin` из **официального** репозитория
   Docker, не из snap: snap-версия иначе видит тома и хуже работает с compose;
3. добавляет вас в группу `docker`, чтобы скрипты вызывали `docker` без sudo;
4. ставит `vm.max_map_count=262144` **постоянно** (через `/etc/sysctl.conf`).
   На чистой Ubuntu там 65530, и Elasticsearch уходит в рестарт-петлю — в логах
   это выглядит как «мало памяти», хотя память ни при чём;
5. включает автозапуск Docker: иначе после перезагрузки сервера контейнеры не
   поднимутся сами;
6. проверяет свободное место и предупреждает, если его меньше 100 ГиБ.

Чего **не** делает: не настраивает firewall, не выпускает сертификат, не меняет SSH,
не поднимает стек. Firewall — шаг 3, сертификат — шаг 6.

После этого нужно **перелогиниться** (или `newgrp docker`), иначе группа ещё не
применена и `docker` попросит sudo.

---

## 2. Инициализация и адрес сервера

```bash
./scripts/first-run.sh --domain mail.example.ru --mail-domain example.ru
```

Без домена — просто `./scripts/first-run.sh`: будут самоподписанный сертификат на
`mail.company.local` и `.env` с уникальными секретами.

**Здесь самая частая ловушка первого запуска.** Сервер открывают по IP, а
`CORVEX_WEB_ORIGINS` по умолчанию содержит только домен. Браузер по `https://<IP>`
получает отказ на каждом запросе, интерфейс молчит, а в логах сервисов нет ни одной
ошибки — потому что это не ошибка сервера, а проверка `Origin`. Поэтому адрес, по
которому вы реально откроете Corvex, надо вписать заранее:

```bash
python3 scripts/ubuntu_origin.py --env .env --ip <IP>
```

Если домен уже есть и он указывает на этот сервер — вместо этого:

```bash
python3 scripts/ubuntu_origin.py --env .env --domain mail.example.ru
```

Скрипт дополняет список, а не затирает его; ручные origin (например,
`http://localhost:8080` для OAuth) остаются. Он же убирает BOM и CRLF из `.env`,
если файл правили в Windows-редакторе.

---

## 3. Firewall

Открывать нужно ровно то, что нужно. Для веба и почты:

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80,443/tcp
sudo ufw allow 25,587/tcp
sudo ufw allow 143,993,995/tcp
sudo ufw enable
sudo ufw status verbose
```

Порты баз (5432, 6379, 5672, 9200) **не открывать**: они работают во внутренней сети
Docker. Порт 110 (POP3 без TLS) не открывается: POP3 работает на 995.

До появления домена и почтового контура достаточно `22,80,443` — почтовые порты
открываются вместе с включением почты (шаг 7).

---

## 4. Предполётная проверка и запуск

```bash
python3 scripts/preflight.py
docker compose up -d --build
docker compose ps
```

Сборка 12 Java-сервисов и фронтенда — 10–30 минут. Первый старт ClamAV скачивает
базы сигнатур, это ещё до 5 минут.

Ждём: все контейнеры, у которых есть healthcheck, в состоянии `healthy`;
`frontend`, `nginx` и `spamd` будут просто `Up` — у них healthcheck нет.

Если что-то `restarting`:

```bash
docker compose logs --tail 100 <сервис>
```

Самое частое на Ubuntu — Elasticsearch (`vm.max_map_count`) и нехватка памяти.
Обе причины `preflight.py` называет заранее.

---

## 5. Первый вход

`https://<IP>` (или `https://mail.example.ru`, если домен уже есть).

Браузер предупредит о самоподписанном сертификате — это ожидаемо, пока нет
Let's Encrypt. Логин — `admin`, пароль либо из `.env`
(`CORVEX_BOOTSTRAP_ADMIN_PASSWORD`), либо напечатанный один раз в логе:

```bash
docker compose logs auth-service | grep BOOTSTRAP
```

Интерфейс попросит сменить пароль. Сразу после входа — **сделать копию**, пока данных
нет и копия занимает мегабайты:

```bash
sudo mkdir -p /srv/backup && sudo chown $USER /srv/backup
./scripts/backup.sh --backup-root /srv/backup
```

Затем — проверить, что копия восстанавливается (это и есть приёмка P3 для Linux):

```bash
./scripts/restore.sh --backup-dir /srv/backup/<время> \
  --project corvex-restore-drill --data-only --force
./scripts/restore_verify.sh --backup-dir /srv/backup/<время> \
  --project corvex-restore-drill
```

Ожидаемый вывод: четыре маркера `<db>_logical_match` и строка
`Restore verification OK`. Рабочий проект при этом не трогается: прогон идёт
в отдельном compose-проекте, приложения не запускаются.

Автоматизация копий — systemd-таймером, пример в [`OPERATIONS.md`](OPERATIONS.md).

---

## 6. Настоящий TLS

Нужны домен и открытый 80. Сертификат выпускается на сам сервер:

```bash
sudo apt-get install -y certbot
docker compose stop nginx
sudo certbot certonly --standalone -d mail.example.ru \
  -m admin@example.ru --agree-tos --no-eff-email
sudo cp /etc/letsencrypt/live/mail.example.ru/fullchain.pem infra/certs/
sudo cp /etc/letsencrypt/live/mail.example.ru/privkey.pem   infra/certs/
sudo chown 100:101 infra/certs/privkey.pem && sudo chmod 600 infra/certs/privkey.pem
docker compose up -d nginx
```

`server_name` в nginx править не нужно: там catch-all `server_name _;`, поэтому
сервер одинаково отвечает и по IP, и по домену. А `CORVEX_DOMAIN` и
`CORVEX_WEB_ORIGINS` должны указывать на домен:

```bash
python3 scripts/ubuntu_origin.py --env .env --domain mail.example.ru
docker compose up -d web-gateway chat-service nginx
```

Продление — `certbot renew` по cron или таймеру плюс копирование файлов и
перезагрузка nginx; порядок и предупреждения — [`OPERATIONS.md`](OPERATIONS.md).

---

## 7. Почта и внешняя доставка

Почта выключена намеренно: `CORVEX_SMTP_ENABLED=false`, `CORVEX_IMAP_ENABLED=false`,
`CORVEX_POP3_ENABLED=false`. Включать раньше, чем есть домен, MX и PTR, нельзя:
IP без PTR и DMARC теряет репутацию быстрее, чем её измеряют.

Порядок: DNS-записи (§7 в [`INSTALL.md`](INSTALL.md)) → PTR у провайдера → PKCS12 для
993/995 (§9 в [`TESTING.md`](TESTING.md)) → включение флагов → `mail-tester` не ниже
9/10 → приёмка клиентов по [`PLAN-TUESDAY.md`](PLAN-TUESDAY.md).

---

## 8. Обновление

```bash
cd /srv/corvex
git pull
python3 scripts/ubuntu_origin.py --env .env --domain mail.example.ru   # если домен есть
python3 scripts/preflight.py
docker compose up -d --build
docker compose ps
```

Перед обновлением — копия (`./scripts/backup.sh`). Миграции Flyway только аддитивные,
поэтому откат кода схему переживает; откат данных — через `restore.sh`.

---

## 9. Что проверено, а что нет

Это Ubuntu-страница, поэтому доказательства важны вдвойне:

| Что | Состояние |
|---|---|
| `prepare-ubuntu.sh --check` | **прогонялся** на Debian 12: находит отсутствие Docker, `vm.max_map_count=65530`, мало места — то есть меряет, а не печатает заготовленное |
| Установка пакетов и Docker | **не проверялась**: в песочнице нет root и сети до `download.docker.com` |
| `ubuntu_origin.py` | **проверен 21 тестом**: BOM, CRLF, ручные origin, идемпотентность, дополнение без затирания |
| `backup.sh` / `restore.sh` / `restore_verify.sh` | синтаксис и логика флагов проверены тестами; **на живом Docker не запускались** |
| `preflight.py` (включая группу docker и автозапуск) | прогонялся, обе новые проверки срабатывают |
| Сам стек под Docker на Ubuntu | **не поднимался**: в песочнице нет Docker |
| Let's Encrypt | не проверялся: нужен домен и открытый 80 |

Так что «теоретически работает» здесь означает: все причины, которые находились в
коде и в конфигурации, устранены и покрыты тестами; живой прогон остаётся за
сервером. Первый запуск покажет остальное, и это будет первый настоящий факт для
[`STATUS.md`](STATUS.md).
