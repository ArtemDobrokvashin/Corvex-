# Подробная инструкция по установке и запуску Corvex

> English version: [INSTALL.en.md](INSTALL.en.md) · Короткая версия для опытных — в [README](../README.md).
> ⚠️ Сначала прочитайте [`READINESS.md`](READINESS.md): что система уже умеет, а что — этапы плана.
> Не направляйте боевые MX-записи на сервер до готовности почтового контура (этапы 2–3).

Инструкция рассчитана на администратора Windows; все команды приведены для
PowerShell, а там, где работа идёт внутри Linux-VM, — для bash (команды одинаковы
для Docker на любом хосте, отличается только установка Docker в шаге 2).

---

## 0. Перед началом: чек-лист

- [ ] Сервер: Windows Server 2019/2022; минимум 8 CPU / 16 ГБ RAM / 500 ГБ SSD,
      рекомендуется 16 CPU / 32 ГБ / 1 ТБ SSD. Для знакомства хватит обычной
      рабочей станции Windows 10/11 с 16 ГБ RAM.
- [ ] Доменное имя для веб-интерфейса и почты (пример в тексте: `company.local`,
      веб-имя `mail.company.local`). Для внешнего приёма почты нужен публичный домен.
- [ ] (Для реальной почты) статический публичный IP; провайдер разрешает входящий
      и **исходящий TCP 25** — проверьте это до всех работ, многие провайдеры его закрывают.
- [ ] Доступ администратора на сервер и право редактировать DNS-зону.
- [ ] Отдельный внешний почтовый ящик для алертов мониторинга (не на этом сервере).

## 1. Сеть и брандмауэр

Наружу Corvex открывает только шесть портов. На периметре (NAT/файрвол) пробросьте
их на сервер, на самом Windows Server откройте в Брандмауэре Защитника Windows:

| Порт | Назначение |
|---|---|
| 443 | Веб-интерфейс, API, WebSocket чата |
| 80 | Редирект на HTTPS + выпуск Let's Encrypt |
| 25 | Приём почты от внешних серверов (пока SMTP выключен по умолчанию — см. шаг 8) |
| 587 | Отправка от почтовых клиентов (SMTP AUTH) |
| 993, 143 | IMAP (этап 3) |
| 995, 110 | POP3 (этап 3) |

Пример для PowerShell (от имени администратора), если Docker стоит прямо на Windows:

```powershell
443,80,25,587,993,143,995,110 | ForEach-Object {
  New-NetFirewallRule -DisplayName "Corvex $_" -Direction Inbound -Protocol TCP -LocalPort $_ -Action Allow
}
```

Если используется Linux-VM в Hyper-V (шаг 2, вариант A), правила создаются для IP
виртуальной машины, а внешний виртуальный коммутатор даёт ей адрес в вашей сети —
проброс портов настраивается как для обычного сервера.

Базы данных, Redis, RabbitMQ и Elasticsearch наружу **не открываются никогда** —
они живут во внутренней сети Docker без опубликованных портов.

## 2. Установка Docker

### Вариант A — продакшен на Windows Server: Linux-VM в Hyper-V (рекомендуется)

Docker Desktop официально не поддерживается на Windows Server, а Linux-контейнеры
нативно на Windows не работают. Рабочий промышленный путь — лёгкая Linux-VM:

1. Включите Hyper-V: Диспетчер серверов → Добавить роли → Hyper-V (или
   `Install-WindowsFeature Hyper-V -IncludeManagementTools -Restart`).
2. Создайте VM: Ubuntu Server 24.04 LTS, поколение 2, vCPU = все минус 2,
   RAM = вся минус 4 ГБ, динамический диск 500 ГБ–1 ТБ на SSD, сетевой адаптер —
   на **внешний** виртуальный коммутатор.
3. Установите Ubuntu (OpenSSH server отметить при установке), внутри VM:

```bash
sudo apt update && sudo apt install -y ca-certificates curl git
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER   # перелогиниться после этого
docker --version && docker compose version
```

Дальнейшие шаги выполняйте в VM по SSH; команды Docker те же, скрипты `.ps1`
заменяются эквивалентами в приложении А к этой инструкции.

### Вариант B — пилот на Windows 10/11: Docker Desktop

1. Установите Docker Desktop с официального сайта, включите WSL2-бэкенд.
2. В Settings → Resources отдайте минимум 6 CPU и 12 ГБ RAM (Elasticsearch, ClamAV
   и 12 JVM на меньшем количестве будут упираться в память).
3. Дальше все действия — в PowerShell как написано ниже.

### Вариант C — чистый Linux-сервер

Если допустимо отказаться от Windows-хоста: установка как в варианте A, шаг 3,
просто без Hyper-V. Это самый простой в эксплуатации вариант.

## 3. Получение исходного кода

```powershell
git clone https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex
git switch main
git pull --ff-only   # завершённые этапы уже в main
```

Если git на сервере нежелателен: скачайте ZIP ветки с GitHub и распакуйте в `C:\Corvex`.

## 4. Первичная инициализация: секреты, ключ JWT, пилотный сертификат

Не копируйте `.env.example` вручную — скрипт ниже сам создаст `.env` со случайными
секретами (у каждого свой), сгенерирует RSA-ключ подписи токенов и самоподписанный
сертификат на 90 дней для пилота:

```powershell
Set-ExecutionPolicy -Scope Process Bypass   # если политики запрещают запуск скриптов
.\scripts\first-run.ps1
```

Ожидаемый вывод: три строки `[OK]`. Проверьте, что создались файлы
`infra\secrets\jwt_private.pem`, `infra\certs\fullchain.pem` и `.env` в корне.

⚠️ Пароли из `.env` применяются к базам **при первом создании томов**. Менять
`POSTGRES_PASSWORD`/`REDIS_PASSWORD` в `.env` после первого запуска бессмысленно —
их придётся менять и внутри самих сервисов. Потеряли контроль на чистом стенде?
`docker compose down -v` удалит тома и позволит начать с нуля (на пилоте без данных).

## 5. Настройка `.env` под вашу компанию

Откройте `.env` в блокноте. Обязательные правки:

| Переменная | Что поставить |
|---|---|
| `CORVEX_DOMAIN` | веб-имя, например `mail.company.local` |
| `CORVEX_WEB_ORIGINS` | обычно оставить пустым: Compose сам подставит `https://$CORVEX_DOMAIN`; заполнять только для дополнительных браузерных origin через запятую |
| `CORVEX_MAIL_DOMAIN` | почтовый домен адресов, например `company.local` |
| `CORVEX_INTERNAL_API_TOKEN` | **обязательный** секрет внутренних auth/admin API; `first-run.ps1` генерирует его и добавляет в старый `.env`, если переменной ещё нет. Не оставлять `change-me-strong-password` |
| `CORVEX_SMTP_ENABLED` | оставить `false` до этапа 2 (защита от потери почты, см. READINESS) |
| `CORVEX_LDAP_URL`, `CORVEX_LDAP_BASE` | заполнить при наличии Active Directory, иначе оставить как есть (логины будут локальными — этап 5 плана) |
| `CORVEX_ALERT_RECIPIENTS` | **внешний** ящик администратора для алертов |
| `ALERT_SMTP_HOST` | SMTP, через который уйдут алерты; желательно внешний резервный |

## 6. Настоящий TLS-сертификат (обязательно перед боевым доступом извне)

Самоподписанный сертификат из шага 4 годится только для внутреннего пилота
(браузеры и почтовые клиенты будут ругаться). Варианты:

- **Публичный домен:** Let's Encrypt. На Windows удобен `win-acme`, на Linux-VM —
  certbot. Готовые файлы положите как `infra\certs\fullchain.pem` (вся цепочка) и
  `infra\certs\privkey.pem`.
- **Внутренний домен:** сертификат вашего корпоративного CA (AD CS). Корневой CA
  должен стоять в доверенных на всех машинах пользователей (обычно уже стоит через GPO).

Имя в сертификате — `mail.<ваш-домен>`: оно одно обслуживает веб, IMAP и POP3.
В `infra\nginx\conf.d\corvex.conf` поправьте `server_name` на это же имя.

## 7. DNS-записи

Для веб-only пилота хватит одной A-записи. Для будущего почтового этапа приготовьте
полный набор сразу (значения для примера домена):

```dns
; Веб и клиенты
mail.company.local.      A      203.0.113.10

; Куда Интернет несёт вам почту
company.local.           MX 10  mail.company.local.

; SPF: кто вправе отправлять от вашего домена (на пилоте ~all, в проде -all)
company.local.           TXT    "v=spf1 mx ~all"

; DKIM: публичный ключ появится в админке на этапе 2 — запись добавите тогда
corvex1._domainkey       TXT    "v=DKIM1; k=rsa; p=<публичный ключ>"

; DMARC: сначала режим наблюдения, через месяц p=quarantine, затем reject
_dmarc.company.local.    TXT    "v=DMARC1; p=none; rua=mailto:dmarc@company.local"

; Автоконфигурация клиентов (Thunderbird/Outlook сами найдут серверы)
autoconfig.company.local.   CNAME mail.company.local.
_imaps._tcp.company.local.  SRV 0 1 993 mail.company.local.
_submission._tcp.company.local. SRV 0 1 587 mail.company.local.
```

Отдельно у провайдера закажите **PTR-запись** (обратный DNS) для публичного IP:
`mail.company.local`. Без неё крупные почтовые системы будут резать вашу почту.

## 8. Первый запуск

```powershell
docker compose up -d --build
```

Первая сборка 12 Java-сервисов и фронтенда занимает 10–30 минут (скачиваются
зависимости Maven и npm). Последующие пересборки одного сервиса — минуты:
`docker compose up -d --build chat-service`.

```powershell
docker compose ps
```

Ждите, пока колонка STATUS у всех сервисов станет `healthy` (первый старт ClamAV
скачивает базы сигнатур — это нормально до 5 минут). Если что-то в `restarting`:

```powershell
docker compose logs --tail 100 <имя-сервиса>
```

Типовые причины первого запуска собраны в разделе «Проблемы» в конце документа.

Мониторинг включается отдельным профилем:

```powershell
docker compose --profile monitoring up -d
```

Grafana: `https://mail.<домен>/grafana/` — только из подсети администраторов
(allowlist в `infra\nginx\conf.d\corvex.conf`, поправьте под свою сеть),
логин `admin`, пароль — `GRAFANA_PASSWORD` из `.env`.

## 9. Проверки после запуска (дымовые тесты)

Выполняйте по порядку; каждый пункт либо проходит, либо даёт конкретную подсказку.

1. **Веб открывается, первый вход.** `https://mail.<домен>` → экран входа Corvex.
   При первом старте создаётся суперадминистратор: логин — `CORVEX_BOOTSTRAP_ADMIN_USERNAME`
   (по умолчанию `admin`), пароль — `CORVEX_BOOTSTRAP_ADMIN_PASSWORD`, а если он пуст,
   пароль сгенерирован и напечатан ОДИН РАЗ в лог:
   `docker compose logs auth-service | Select-String BOOTSTRAP`.
   Интерфейс заставит сменить пароль, после чего в Админ-панели заводятся пользователи и группы.
   Кнопка «Посмотреть демо без сервера» — только витрина интерфейса, не настоящий вход.
2. **Здоровье API.** В PowerShell: `curl.exe -k https://mail.<домен>/api/v1/auth/me`
   → ожидается `401` (gateway жив и требует токен).
3. **JWKS.** `curl.exe http://localhost:8080` не используйте снаружи — изнутри:
   `docker compose exec web-gateway wget -qO- http://auth-service:8081/oauth2/jwks`
   → JSON с публичным ключом.
4. **Базы применили миграции.** `docker compose logs auth-service | Select-String Flyway`
   → строки об успешных миграциях; аналогично `admin-service`, `chat-service`,
   `mail-storage-service`.
5. **SMTP выключен (так задумано).** `docker compose logs mail-smtp-service`
   содержит `SMTP listener disabled`. Это норма до этапа 2.
6. **Алертинг.** `docker compose stop chat-service`, через 2–4 минуты письмо
   `[Corvex critical] ServiceDown` должно прийти на `CORVEX_ALERT_RECIPIENTS`
   (если настроен рабочий `ALERT_SMTP_HOST`). Вернуть: `docker compose start chat-service`.

## 10. Повседневная эксплуатация

```powershell
docker compose ps                          # состояние всех сервисов
docker compose logs -f --tail 100          # все логи потоком (или Loki в Grafana)
docker compose restart <сервис>            # перезапуск одного сервиса
docker compose up -d --build <сервис>      # пересборка одного сервиса после обновления кода
docker compose down                        # полная остановка (данные в томах сохраняются)
```

Обновление версий Corvex: `git pull` → `docker compose up -d --build`.
Миграции БД применит Flyway сам. Откат: вернуть предыдущий коммит/теги образов
и повторить `up -d`; миграции только аддитивные, схема откат переживает.

## 11. Резервное копирование

1. Создайте задание в Планировщике Windows: ежедневно в 02:30 запускать
   `powershell -ExecutionPolicy Bypass -File C:\Corvex\scripts\backup.ps1 -BackupRoot D:\CorvexBackup`.
2. Копии складывайте на **другой физический носитель** (сетевой диск/внешний HDD):
   бэкап на том же SSD не спасает от смерти диска и шифровальщиков.
3. Скрипт сохраняет: дампы 4 баз PostgreSQL, архивы томов с письмами, вложениями и
   файлами чата, `.env`, сертификаты и ключ JWT. Индекс Elasticsearch в бэкап
   не входит — перестраивается из баз.
4. Раз в квартал делайте учебное восстановление на стенде: поднять стек из копий
   и убедиться, что ящики/чаты на месте. Бэкап без проверенного восстановления —
   не бэкап.

## 12. Настройки безопасности «по умолчанию» — проверьте перед доступом извне

- [ ] В `.env` нет ни одного значения `change-me...`.
- [ ] TLS — настоящий сертификат (шаг 6), не самоподписанный пилот.
- [ ] В nginx поправлены `server_name` и подсеть доступа к `/grafana/`.
- [ ] Алерты уходят на внешний ящик и реально приходят (проверка из шага 9.6).
- [ ] Бэкап настроен и одна копия лежит вне сервера.
- [ ] `CORVEX_SMTP_ENABLED=false`, пока не закрыт этап 2; MX боевой не опубликован.

## Проблемы и решения

| Симптом | Что проверить/делать |
|---|---|
| Сборка Maven падает на одном сервисе | `docker compose build <сервис>` без `-d` и читать первую ошибку компиляции; зафиксируйте вывод — это обратная связь в репозиторий |
| Контейнер unhealthy/restarting | `docker compose logs --tail 100 <сервис>`: обычно либо пароль из `.env` не совпал с уже инициализированным томом (менять в сервисе либо `down -v` на пустом стенде), либо мало памяти |
| nginx: `cannot load certificate` | Нет файлов в `infra\certs` — выполните шаг 4 (first-run) или шаг 6 |
| Очень долго стартует clamav | Первый запуск скачивает ~200 МБ сигнатур; если Интернета нет, базы приносятся вручную в том `clamav-data` |
| Elasticsearch уходит в restart | Мало RAM (нужно ≥2,5 ГБ только ему) или `vm.max_map_count` в Linux-VM: `sudo sysctl -w vm.max_map_count=262144` и вписать в `/etc/sysctl.conf` |
| Веб открывается, но плашка «демо-режим» | Вы зашли кнопкой демо, либо бэкенд недоступен. Выйдите (кнопка в плашке/сайдбаре) и войдите настоящей учёткой; проверьте `docker compose ps` и логи auth-service |
| Внешние письма не приходят | Этап 2 не готов и SMTP выключен осознанно. После готовности: порты 25 извне, MX, PTR, проверка mail-tester.com ≥ 9/10 |

## Приложение А. Эквивалент first-run.ps1 для Linux (VM/сервер)

```bash
mkdir -p infra/secrets infra/certs
[ -f infra/secrets/jwt_private.pem ] || docker run --rm -v "$PWD/infra/secrets:/out" alpine sh -c \
  "apk add --no-cache openssl >/dev/null && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out /out/jwt_private.pem"
[ -f infra/certs/fullchain.pem ] || docker run --rm -v "$PWD/infra/certs:/out" alpine sh -c \
  "apk add --no-cache openssl >/dev/null && openssl req -x509 -newkey rsa:2048 -keyout /out/privkey.pem -out /out/fullchain.pem -days 90 -nodes -subj '/CN=mail.company.local'"
# .env со случайным секретом на каждую переменную
while IFS= read -r line; do
  case "$line" in
    *=change-me-strong-password) echo "${line%%=*}=$(openssl rand -hex 24)" ;;
    CORVEX_TOTP_ENC_KEY=*)       echo "CORVEX_TOTP_ENC_KEY=$(openssl rand -base64 32)" ;;
    *) echo "$line" ;;
  esac
done < .env.example > .env
```

Дальше — шаги 5–12 этой инструкции, команды Docker идентичны.
