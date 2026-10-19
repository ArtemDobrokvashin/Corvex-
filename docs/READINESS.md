# Готовность к запуску на сервере / Production readiness

**Короткий ответ: этап 1 выполнен, Stages 2A/2B реализованы в коде — входящий и
исходящий почтовые конвейеры, submission 587, DKIM, retry/DSN и настоящий webmail
на `/api/v1/mail/**`. Как рабочую корпоративную почту для сотрудников — пока нет:
нужен runtime-smoke на JDK/Docker-хосте, затем IMAP/POP3 этапа 3.** Ниже — честный
аудит по состоянию этого коммита, включая блокеры, исправленные при аудите, и
обязательные ворота перед пилотом. Полный реестр находок октября 2026 года:
[`12-full-audit-2026-10.md`](12-full-audit-2026-10.md).

_English summary: Identity & admin (Stage 1) and Stages 2A/2B mail are implemented in
code: durable inbound spooling, spam/AV/Maildir delivery, outbound MX queue with
retry/DSN, DKIM signing, authenticated submission, and a server-backed webmail. It is
still **not** a production mail server: the Java/Docker runtime gate and Stage 3
IMAP/POP3 remain. Do not point production MX records at it yet. SMTP stays disabled by
default until the runtime smoke passes._

Обозначения: 🟢 готово · 🟡 каркас/частично, стартует · 🔴 не реализовано

## 1. Статус по компонентам

| Компонент | Статус | Что именно |
|---|---|---|
| Frontend (почта/чат/админка) | 🟢 вход/почта/админка / 🟡 чат | Собирается (modern + legacy для Chrome 109), 12 unit-тестов проходят. Интерфейс полностью RU/EN, есть нативный Unicode emoji-picker и графемно-безопасная работа с текстом. Вход, смена пароля, 2FA, пароли приложений, админ-панель и webmail работают с настоящим API; демо-режим — только явной кнопкой витрины. Чат пока демонстрационный до этапа 4, о чём предупреждает плашка модуля |
| Docker Compose (25 сервисов) | 🟡 | Вся инфраструктура и порядок старта по healthcheck описаны; в этой песочнице нет Docker, поэтому **фактический `docker compose up` на вашем сервере будет первым настоящим дымовым тестом** |
| Инфраструктура (PostgreSQL, Redis, RabbitMQ, Elasticsearch, SpamAssassin, ClamAV, nginx, Prometheus/Grafana/Loki) | 🟢 конфиги | Официальные образы, сети, тома, алерты настроены. Требуется первый реальный запуск для подтверждения |
| Схемы БД (Flyway) | 🟢 | 4 базы, миграции и сид групп/прав готовы |
| auth-service | 🟢 этап 1 | Полный контур входа: сущности пользователей, Argon2id, login (lockout после 5 неудач/15 мин), вращающиеся refresh в HttpOnly cookie с детектом кражи, /me, смена пароля, TOTP setup/confirm/disable без опасного повторного setup, пароли приложений, internal API (CRUD для админки, verify-password для протоколов, статистика), сид суперадмина при первом старте. Публичные ошибки локализуются RU/EN; `/internal/**` защищён fail-closed `X-Corvex-Internal-Token`. Осталось на этап 5: AD/LDAP и политики из админки |
| web-gateway | 🟢 | Маршруты, JWT-проверка по JWKS и rate-limit работают с реальными токенами; ошибочный `SCOPE_admin` убран — права по claim `permissions` проверяет admin-service. Rate-limit теперь видит реальный IP из доверенного nginx, а logout доступен с валидной refresh-cookie после истечения access token |
| mail-smtp-service | 🟡 Stages 2A/2B в коде | SubEtha-приём: RCPT проверяется по домену и существующему активному ящику auth-service; DATA потоково пишется в durable spool storage (лимит до heap), событие публикуется с publisher confirm, при недоступности storage/RabbitMQ — SMTP `451`. Добавлены исходящая MX-очередь (dnsjava, STARTTLS, retry 5m/15m/1h/4h до 72ч, DSN), DKIM rsa-sha256 relaxed/relaxed и submission 587 с обязательным STARTTLS+AUTH и проверкой прав/отправителя. `CORVEX_SMTP_ENABLED=false` по умолчанию до runtime-smoke |
| mail-imap / mail-pop3 | 🔴 | Порты опубликованы, протокольных движков (Apache James за портами) ещё нет — Outlook/Thunderbird не подключатся (этап 3) |
| mail-storage-service | 🟡 Stages 2A/2B в коде | Internal raw API (`POST/GET/DELETE /internal/raw`), потребитель `mail.av.checked`, MIME-разбор, идемпотентная доставка по `raw_ref+owner`, Maildir+CAS вложений, папки/UID под блокировкой, квота по auth+mail_db, индексация Elasticsearch и webmail API `/api/v1/mail/**`: folders/list/detail/flags/move/send, Sent-копия, локальная/внешняя маршрутизация и генерация DSN по `mail.outbound.bounced`. Нужна живая проверка Maven/Docker |
| mail-spam / mail-antivirus | 🟡 Stage 2A в коде | Потребители конвейера подключены: spam читает spool и публикует `mail.spam.checked` (fail-open при недоступном spamd), AV потоково сканирует ClamAV INSTREAM и публикует `mail.av.checked` (ERROR блокирует вложения по статусу). Очереди durable, 5 retry, DLQ |
| chat-service | 🟡 | STOMP-конфигурация и контроллер есть; персистенции сообщений, проверки членства, REST истории нет (этап 4). В UI чат работает на демо-состоянии |
| chat-file-service | 🟡 | Только каркас приложения |
| admin-service | 🟢 этап 1 | REST по контракту: пользователи (через защищённый токеном internal API auth, с компенсацией при сбое), группы и чекбоксы 24 прав, назначение групп, валидируемые одной транзакцией настройки KV, аудит всех действий, сводное здоровье сервисов, базовая аналитика по пользователям/входам, запрос бэкапа в аудит. Последнего SUPER_ADMIN нельзя лишить членства, заблокировать или отключить. Внутренний access-эндпоинт отдаёт auth группы и права для JWT; при старте назначает бутстрап-пользователю SUPER_ADMIN |
| notification-service | 🟡 | Цепочка Alertmanager → вебхук → очередь → email теперь замкнута кодом; не тестировалась живьём |
| Скрипты PowerShell | 🟡 | `first-run.ps1`/`backup.ps1` написаны аккуратно, но на настоящем Windows не выполнялись |
| Сборка Java | 🟡 | Найденные аудитом ошибки компиляции/контекста исправлены статически (см. §2), но в песочнице **невозможна проверка `mvn package`**: нет JDK, а Maven Central сетью песочницы заблокирован. Первая сборка образов на сервере — обязательный гейт |

## 2. Блокеры запуска, найденные и исправленные этим аудитом

До аудита `docker compose up --build` **гарантированно падал**. Исправлено:

1. `chat-service` не компилировался: импорты Spring Security/OAuth2 при отсутствующих зависимостях → добавлены в `pom.xml`.
2. `auth-service` не стартовал: `TokenService` требовал бин `JwtEncoder`, которого не было → `JwtKeyConfig` (ключ из PEM + эфемерный dev-режим), `AuthSecurityConfig`, JWKS-эндпоинт.
3. `mail-smtp-service` не собирал контекст: не было бинов `LocalDomainPolicy`/`RawMessageStoreClient`, никто не запускал сам SMTP-сервер, сигнатура `data()` не соответствовала SubEtha 3.1.7 → `SmtpServerConfig`, исправлена сигнатура.
4. `web-gateway` не создавал маршруты: SpEL ссылался на несуществующие `ipKeyResolver`/`userKeyResolver`, фильтр CircuitBreaker без зависимости resilience4j, `lb:ws` без loadbalancer → добавлены бины, маршруты упрощены до прямого `ws://`, лишний AMQP убран из pom.
5. `notification-service`: не создавался `JavaMailSender` (не было `spring.mail`), очередь `corvex.alerts` не декларировалась, вебхука Alertmanager не было → конфиг + `NotificationConfig`.
6. Redis/Elasticsearch **не были прокинуты** в Java-сервисы: healthcheck навсегда оставался DOWN и зависимые сервисы не стартовали → `REDIS_*`/`ELASTIC_PASSWORD` в общим env compose + маппинги в `application.yml` сервисов.
7. Плагин `rabbitmq_stomp` (брокер-реле чата) никогда не включался (комментарий обещал несуществующую обёртку) → `infra/rabbitmq/enabled_plugins`.
8. nginx падал при старте без профиля monitoring (upstream `grafana` не резолвился) → ленивый резолв через Docker DNS.
9. `first-run.ps1`: все пароли заменялись **одним** значением, а очередь действий из старого README вообще пропускала генерацию → скрипт переписан (уникальный секрет на переменную, генератор на .NET RNG), инструкции синхронизированы.
10. Защита от потери почты: SMTP-листенер выключен по умолчанию до готовности хранилища.

## 3. Что можно делать с системой прямо сейчас

- ✅ Развернуть весь стек на тестовом сервере/VM и проверить здоровье контейнеров (`docs compose ps`) — ожидается, что все сервисы станут `healthy`; **это ваш первый настоящий гейт, в песочнице он не пройден по техническим причинам (нет Docker/JDK)**;
- ✅ Входить суперадмином (пароль первого старта: `docker compose logs auth-service | Select-String BOOTSTRAP` или `CORVEX_BOOTSTRAP_ADMIN_PASSWORD`), менять пароль, заводить пользователей и группы в админке, включать себе 2FA;
- ✅ Смотреть и принимать веб-интерфейс (демо-режим помечен плашкой);
- ✅ Проверять инфраструктуру: PostgreSQL принимает миграции Flyway, Grafana/Prometheus/Loki поднимаются, алерт при остановке контейнера уходит (после настройки `ALERT_SMTP_*`);
- ✅ Готовить DNS и сертификаты впрок (docs/INSTALL), не публикуя MX в прод.

## 4. Что НЕЛЬЗЯ делать до этапов 2–3 плана

- ⛔ Направлять настоящие MX-записи и переносить рабочие ящики;
- ⛔ Включать `CORVEX_SMTP_ENABLED=true` с внешним доступом к 25 порту;
- ⛔ Давать сотрудникам доступ как к боевой «почте»: webmail уже серверный в коде, но Java/Docker runtime-smoke ещё не пройден, а сообщений чата на сервере пока нет;
- ⛔ Подключать Outlook/Thunderbird (IMAP/POP3 движков нет).

## 5. Ворота готовности к пилоту (50 ящиков)

- [ ] `mvn` сборка всех образов на целевом сервере зелёная, `docker compose ps` — все `healthy` 72 часа без рестартов;
- [x] Этап 1: login/refresh/me, Argon2id+lockout, создание пользователей из админки, группы/права, аудит, сид суперадмина — **реализован в коде**, остаётся живая проверка сборкой на сервере;
- [x] RU/EN интерфейс и ошибки API, emoji/Unicode-тесты, internal API token, доверенный клиентский IP и защита последнего SUPER_ADMIN — **реализованы в коде**; Java-тесты локализаторов/фильтра ждут первого Maven-запуска;
- [x] Stage 2A в коде: потоковое сохранение .eml в durable spool, конвейер spam→AV→доставка с DLQ, MIME/Maildir/CAS/квота/Elasticsearch, read-side webmail API, anti-open-relay на RCPT + unit-тест политики доменов;
- [ ] Runtime Stage 2A: на JDK/Docker-хосте письмо проходит SMTP→spool→spam→AV→Maildir→ES, переживает рестарт контейнеров и не дублируется при повторной доставке события;
- [x] Stage 2B в коде: submission 587 (AUTH+STARTTLS, anti-spoofing, права), webmail send/move, исходящая MX-очередь с retry/DSN, DKIM-подпись и DNS-инструкции SPF/DKIM/DMARC;
- [ ] Runtime Stage 2B: webmail отправляет локально и во внешний MX; submission принимает app-password по STARTTLS; внешняя проверка подтверждает DKIM/SPF/DMARC; при 4xx письмо retry-ится, при 5xx отправитель получает DSN;
- [ ] Этап 3: IMAP поверх James (IDLE/UIDPLUS/MOVE/QUOTA), POP3, app-passwords; приёмка в Outlook и Thunderbird;
- [ ] DNS по чек-листу + mail-tester ≥ 9/10;
- [ ] Бэкап и **учебное восстановление** выполнены на стенде (docs/10 §10.5);
- [ ] Только после всего выше: `CORVEX_SMTP_ENABLED=true`, пилотный поддомен, прогрев IP.

## 6. Соответствие критериев приёмки из ТЗ (§11) на сегодня

| Критерий | Сегодня |
|---|---|
| Сервисы запускаются через docker-compose | Ожидаемо после фикса блокеров; требуется подтверждение первым реальным запуском |
| Почта SMTP/IMAP/POP3 работает | SMTP inbound/submission/outbound — Stages 2A/2B в коде, ждут runtime-smoke; IMAP/POP3 — этап 3 |
| Веб-клиент показывает/отправляет письма | Да в коде: серверные folders/list/detail/flags/move/send; демо-данные только при явном demo-входе |
| Чат realtime | UI — демо; STOMP-каркас есть, персистенция — этап 4 |
| Админка и права | Да (код этапа 1): пользователи, группы/чекбоксы, аудит; нужна живая сборка на сервере |
| Поиск < 1 с | Elasticsearch-индексация письма при доставке есть в коде; замер latency на боевом объёме — runtime-гейт |
| Алертинг email | Цепочка закодирована, нужна живая проверка |
| Chrome 109 / адаптив | Да, сборка проверена (legacy-бандл) |
