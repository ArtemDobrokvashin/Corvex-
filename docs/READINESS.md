# Готовность к запуску на сервере / Production readiness

**Короткий ответ: этап 1 выполнен, Stages 2A/2B и Stage 4 реализованы в коде — входящий и
исходящий почтовые конвейеры, submission 587, DKIM, retry/DSN, настоящий webmail
на `/api/v1/mail/**`, а также persistent-чат на `/api/v1/chat/**`, `/ws/chat` и
`/api/v1/files/**`. Этап 3 реализован в коде полностью: внутренний протокольный API хранилища,
POP3 и IMAP-движок на Apache James написаны и выключены по умолчанию до сборки и клиентского smoke.
Как рабочую корпоративную почту для
сотрудников — пока нет: нужен runtime-smoke на JDK/Docker-хосте.** Ниже — честный
аудит по состоянию этого коммита, включая блокеры, исправленные при аудите, и
обязательные ворота перед пилотом. Полный реестр находок октября 2026 года:
[`12-full-audit-2026-10.md`](12-full-audit-2026-10.md); зафиксированный
dependency-baseline, исключения и порядок G0–G5 —
[`RELEASE-DEPENDENCIES.md`](RELEASE-DEPENDENCIES.md).

_English summary: Identity & admin (Stage 1), Stages 2A/2B mail, and Stage 4 persistent
chat are implemented in code: durable inbound spooling, spam/AV/Maildir delivery,
outbound MX queue with retry/DSN, DKIM signing, authenticated submission, a
server-backed webmail, and PostgreSQL-backed rooms/messages with STOMP realtime,
presence, reactions/pins/read receipts and antivirus-checked files. It is still **not**
a production mail/chat server: the Java/Docker runtime gate remains, Stage 3 has the
storage protocol API plus James-based POP3/IMAP in code (disabled by default until the
Maven build and mail-client smoke pass on a JDK host).
Do not point production MX records at it yet. SMTP stays disabled by default until the
runtime smoke passes._

Обозначения: 🟢 готово · 🟡 каркас/частично, стартует · 🔴 не реализовано

## 1. Статус по компонентам

| Компонент | Статус | Что именно |
|---|---|---|
| Frontend (почта/чат/админка) | 🟢 вход/почта/админка / 🟡 чат | Собирается (React 19/Vite 8, modern + legacy для Chrome 109), 15 unit-тестов проходят, production `npm audit` — 0. Интерфейс полностью RU/EN, есть нативный Unicode emoji-picker и графемно-безопасная работа с текстом. Вход, смена пароля, 2FA, пароли приложений, админ-панель и webmail работают с настоящим API; демо-режим — только явной кнопкой витрины. В реальной сессии чат использует REST/STOMP Stage 4: история, комнаты, direct/группы, реакции, pin/edit/delete, read/typing/presence, поиск и вложения; демо-экран остался только в явном demo-режиме, а Java/Docker runtime всё ещё не подтверждён |
| Docker Compose (25 сервисов) | 🟡 | Вся инфраструктура и порядок старта по healthcheck описаны; в текущей среде разработки нет Docker, поэтому **фактический `docker compose up` на вашем сервере будет первым настоящим дымовым тестом** |
| Инфраструктура (PostgreSQL, Redis, RabbitMQ, Elasticsearch, SpamAssassin, ClamAV, nginx, Prometheus/Grafana/Loki) | 🟢 конфиги | Официальные образы, сети, тома, алерты настроены. Требуется первый реальный запуск для подтверждения |
| Схемы БД (Flyway) | 🟢 | 4 базы, миграции и сид групп/прав готовы |
| auth-service | 🟢 этап 1 | Полный контур входа: сущности пользователей, Argon2id, login (lockout после 5 неудач/15 мин), вращающиеся refresh в HttpOnly cookie с детектом кражи, /me, смена пароля, TOTP setup/confirm/disable без опасного повторного setup, пароли приложений, internal API (CRUD для админки, verify-password для протоколов, статистика), сид суперадмина при первом старте. Публичные ошибки локализуются RU/EN; `/internal/**` защищён fail-closed `X-Corvex-Internal-Token`. Осталось на этап 5: AD/LDAP и политики из админки |
| web-gateway | 🟢 | Маршруты, JWT-проверка по JWKS и rate-limit работают с реальными токенами; ошибочный `SCOPE_admin` убран — права по claim `permissions` проверяет admin-service. Rate-limit теперь видит реальный IP из доверенного nginx, а logout доступен с валидной refresh-cookie после истечения access token |
| mail-smtp-service | 🟡 Stages 2A/2B в коде | Поддерживаемый SubEtha-форк 7.2.2 (код адаптирован к его Builder/Optional API по исходникам релиза), приём: RCPT проверяется по домену и существующему активному ящику auth-service; DATA потоково пишется в durable spool storage (лимит до heap), событие публикуется с publisher confirm, при недоступности storage/RabbitMQ — SMTP `451`. Добавлены исходящая MX-очередь (dnsjava, STARTTLS, retry 5m/15m/1h/4h до 72ч, DSN), DKIM rsa-sha256 relaxed/relaxed и submission 587 с обязательным STARTTLS+AUTH и проверкой прав/отправителя. `CORVEX_SMTP_ENABLED=false` по умолчанию до runtime-smoke |
| mail-imap / mail-pop3 | 🟡 Этап 3 в коде полностью | Внутренний протокольный API в mail-storage (папки/UID/MODSEQ/флаги/expunge/APPEND/COPY-MOVE/квота), POP3 на Apache James 3.8.2 (снапшот INBOX, UIDL=UUID письма, DELE на QUIT, implicit TLS 995, plain 110 только по явному флагу) и IMAP-движок на Apache James IMAP (MailboxManager/MessageManager поверх storage API, LOGIN через auth, LIST/CREATE/RENAME, SELECT/FETCH/STORE/EXPUNGE/APPEND/SEARCH/COPY/MOVE, QUOTA, CONDSTORE по MODSEQ, 143 STARTTLS/993). `CORVEX_POP3_ENABLED=false` и `CORVEX_IMAP_ENABLED=false` по умолчанию до сборки и клиентского smoke |
| mail-storage-service | 🟡 Stages 2A/2B в коде | Internal raw API (`POST/GET/DELETE /internal/raw`), потребитель `mail.av.checked`, MIME-разбор, идемпотентная доставка по `raw_ref+owner`, Maildir+CAS вложений, папки/UID под блокировкой, квота по auth+mail_db, индексация Elasticsearch и webmail API `/api/v1/mail/**`: folders/list/detail/flags/move/send, Sent-копия, локальная/внешняя маршрутизация и генерация DSN по `mail.outbound.bounced`. Нужна живая проверка Maven/Docker |
| mail-spam / mail-antivirus | 🟡 Stage 2A в коде | Потребители конвейера подключены: spam читает spool и публикует `mail.spam.checked` (fail-open при недоступном spamd), AV потоково сканирует ClamAV INSTREAM и публикует `mail.av.checked` (ERROR блокирует вложения по статусу). Очереди durable, 5 retry, DLQ |
| chat-service | 🟡 Stage 4 в коде | Flyway V2, JPA-сущности/репозитории комнат, участников, сообщений, edit-history, реакций и упоминаний; REST для rooms/direct/members, cursor-history, идемпотентной отправки по `clientMessageId`, edit/soft-delete, reactions, pin, монотонных read-позиций, membership-scoped поиска, каталога и presence. STOMP: Bearer JWT на `CONNECT`, перепроверка членства/права записи на `SUBSCRIBE`/`SEND`, события после коммита БД, typing и mention/queue ошибок. Redis presence с TTL сессии 120 с. Unit-тесты написаны, но Maven/Spring/Flyway runtime ещё не подтверждён |
| chat-file-service | 🟡 Stage 4 в коде | JWT resource-server, потоковая загрузка до 25 MiB с SHA-256, синхронный fail-closed ClamAV INSTREAM, content-addressable blob + атомарно публикуемые JSON-метаданные на persistent volume. Upload требует `CHAT_SEND_FILES` и актуальное writable-членство; download каждый раз перепроверяет членство через internal API chat-service и не является публичной ссылкой. Unit-тесты написаны; Maven/Docker/ClamAV runtime ещё не подтверждён |
| admin-service | 🟢 этап 1 | REST по контракту: пользователи (через защищённый токеном internal API auth, с компенсацией при сбое), группы и чекбоксы 24 прав, назначение групп, валидируемые одной транзакцией настройки KV, аудит всех действий, сводное здоровье сервисов, базовая аналитика по пользователям/входам, запрос бэкапа в аудит. Последнего SUPER_ADMIN нельзя лишить членства, заблокировать или отключить. Внутренний access-эндпоинт отдаёт auth группы и права для JWT; при старте назначает бутстрап-пользователю SUPER_ADMIN |
| notification-service | 🟡 | Цепочка Alertmanager → вебхук → очередь → email теперь замкнута кодом; не тестировалась живьём |
| Скрипты PowerShell | 🟡 | `first-run.ps1`/`backup.ps1` написаны аккуратно, но на настоящем Windows не выполнялись |
| Сборка Java | 🟡 | Найденные аудитом ошибки компиляции/контекста исправлены статически (см. §2). Для первого compile-baseline закреплены Spring Boot 3.2.12 + Spring Cloud 2023.0.6 (финальные patch/SR своей линии), James 3.8.2, поддерживаемый SubEtha 7.2.2, dnsjava 3.6.5 и JaCoCo 0.8.15; неиспользуемые Swagger/GreenMail/MIME4J удалены, отсутствовавшее `james.version` в POP3 добавлено. В текущей среде разработки нет JDK/Maven, поэтому `mvn package` ещё не выполнен. Это baseline только для первой честной компиляции: переход на поддерживаемые Spring Boot 4.1/Spring Cloud/Jackson 3 и James 3.9.x — обязательный отдельный G4-гейт до пилота (`RELEASE-DEPENDENCIES.md`) |

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

Перед первым полным тестом дополнительно закрыт release-hardening пласт:
frontend переведён на React 19/Vite 8/Vitest 5/TypeScript 7 с production audit 0;
в POP3 определён `james.version`; заброшенный SubEtha 3.1.7 заменён поддерживаемым
форком 7.2.2 с адаптацией кода; неиспользуемые зависимости удалены; Docker-образы
RabbitMQ, Elasticsearch, SpamAssassin, ClamAV, nginx и monitoring подняты до
поддерживаемых рядов. Точная матрица и оставшиеся исключения — в
[`RELEASE-DEPENDENCIES.md`](RELEASE-DEPENDENCIES.md).

## 3. Что можно делать с системой прямо сейчас

- ✅ Развернуть весь стек на тестовом сервере/VM и проверить здоровье контейнеров (`docs compose ps`) — ожидается, что все сервисы станут `healthy`; **это ваш первый настоящий G1/G2-гейт; в текущей среде разработки он не пройден из-за отсутствия Docker/JDK**;
- ✅ Входить суперадмином (пароль первого старта: `docker compose logs auth-service | Select-String BOOTSTRAP` или `CORVEX_BOOTSTRAP_ADMIN_PASSWORD`), менять пароль, заводить пользователей и группы в админке, включать себе 2FA;
- ✅ Смотреть и принимать веб-интерфейс (демо-режим помечен плашкой);
- ✅ Проверять инфраструктуру: PostgreSQL принимает миграции Flyway, Grafana/Prometheus/Loki поднимаются, алерт при остановке контейнера уходит (после настройки `ALERT_SMTP_*`);
- ✅ Готовить DNS и сертификаты впрок (docs/INSTALL), не публикуя MX в прод.

## 4. Что НЕЛЬЗЯ делать до runtime-гейтов и этапа 3

- ⛔ Направлять настоящие MX-записи и переносить рабочие ящики;
- ⛔ Включать `CORVEX_SMTP_ENABLED=true` с внешним доступом к 25 порту;
- ⛔ Давать сотрудникам доступ как к боевой «почте» или «корпоративному чату»: webmail и persistent-чат уже серверные в коде, но Java/Docker runtime-smoke, Flyway/Spring-старт и сквозная проверка STOMP/файлов ещё не пройдены;
- ⛔ Подключать Outlook/Thunderbird как боевые клиенты до runtime-гейтов: IMAP/POP3 написаны, но ещё не прошли `mvn verify`, старт Spring и клиентский smoke на JDK-хосте — на стенде включать только осознанно.

## 5. Ворота готовности к пилоту (50 ящиков)

- [ ] `mvn` сборка всех образов на целевом сервере зелёная, `docker compose ps` — все `healthy` 72 часа без рестартов;
- [ ] G4 dependency-платформа: поддерживаемые Spring Boot 4.1/Spring Cloud/Jackson 3 и James 3.9.x отдельным апгрейдом после первого зелёного baseline, с повторными Maven/Docker/mail/client smoke; Promtail заменён на Grafana Alloy, если monitoring включается в production;
- [x] Этап 1: login/refresh/me, Argon2id+lockout, создание пользователей из админки, группы/права, аудит, сид суперадмина — **реализован в коде**, остаётся живая проверка сборкой на сервере;
- [x] RU/EN интерфейс и ошибки API, emoji/Unicode-тесты, internal API token, доверенный клиентский IP и защита последнего SUPER_ADMIN — **реализованы в коде**; Java-тесты локализаторов/фильтра ждут первого Maven-запуска;
- [x] Stage 2A в коде: потоковое сохранение .eml в durable spool, конвейер spam→AV→доставка с DLQ, MIME/Maildir/CAS/квота/Elasticsearch, read-side webmail API, anti-open-relay на RCPT + unit-тест политики доменов;
- [ ] Runtime Stage 2A: на JDK/Docker-хосте письмо проходит SMTP→spool→spam→AV→Maildir→ES, переживает рестарт контейнеров и не дублируется при повторной доставке события;
- [x] Stage 2B в коде: submission 587 (AUTH+STARTTLS, anti-spoofing, права), webmail send/move, исходящая MX-очередь с retry/DSN, DKIM-подпись и DNS-инструкции SPF/DKIM/DMARC;
- [ ] Runtime Stage 2B: webmail отправляет локально и во внешний MX; submission принимает app-password по STARTTLS; внешняя проверка подтверждает DKIM/SPF/DMARC; при 4xx письмо retry-ится, при 5xx отправитель получает DSN;
- [x] Stage 4 в коде: Flyway V2, persistent комнаты/сообщения/реакции/упоминания, cursor-history и идемпотентный `clientMessageId`, STOMP с Bearer на CONNECT и перепроверкой SUBSCRIBE/SEND, presence, настоящий frontend без demo-данных в реальной сессии, файлы CAS+ClamAV с проверкой членства на upload/download; unit-тесты сервисов чата и файлов написаны;
- [ ] Runtime Stage 4: на JDK/Docker-хосте два пользователя обмениваются сообщениями в direct/группе, история переживает рестарт, не-участник не читает комнату и не скачивает файл, повтор `clientMessageId` не создаёт дубль, EICAR отклоняется антивирусом, STOMP переживает refresh JWT;
- [x] Этап 3 в коде: внутренний протокольный API mail-storage (папки/UID/MODSEQ/флаги/expunge/APPEND/COPY-MOVE/квота, owner-скоупинг, защита системных папок, RENAME со сменой родителя), POP3 и IMAP на Apache James 3.8.2 (снапшот INBOX/UIDL для POP3; MailboxManager/MessageManager, QUOTA/CONDSTORE, 143 STARTTLS/993 для IMAP); unit-тесты написаны, серверы выключены по умолчанию;
- [ ] Runtime этапа 3: `mvn` собирает James-зависимости; Thunderbird/Outlook проходят IMAP 993/143 (LOGIN c app-password, LIST/SELECT/FETCH, отправка сохраняет копию в Sent, MOVE/DELETE/EXPUNGE, IDLE) и POP3 995 (DELE без QUIT ничего не удаляет, повторный логин не дублирует письма при «оставлять на сервере»);
- [ ] (опционально после smoke) персистентные LSUB-подписки и распределённый IDLE из SMTP/storage переезжают в этап 5 hardening;
- [ ] DNS по чек-листу + mail-tester ≥ 9/10;
- [ ] Бэкап и **учебное восстановление** выполнены на стенде (docs/10 §10.5);
- [ ] Только после всего выше: `CORVEX_SMTP_ENABLED=true`, пилотный поддомен, прогрев IP.

## 6. Соответствие критериев приёмки из ТЗ (§11) на сегодня

| Критерий | Сегодня |
|---|---|
| Сервисы запускаются через docker-compose | Ожидаемо после фикса блокеров; требуется подтверждение первым реальным запуском |
| Почта SMTP/IMAP/POP3 работает | SMTP inbound/submission/outbound — Stages 2A/2B в коде; протокольный API хранилища, POP3 и IMAP-движок (James) — этап 3 в коде полностью; всё ждёт runtime-smoke на JDK/Docker и приёмку почтовыми клиентами |
| Веб-клиент показывает/отправляет письма | Да в коде: серверные folders/list/detail/flags/move/send; демо-данные только при явном demo-входе |
| Чат realtime | Stage 4 в коде: серверная персистенция, авторизованный STOMP, настоящий UI в реальной сессии и файлы; требуется Maven/Docker runtime-smoke |
| Админка и права | Да (код этапа 1): пользователи, группы/чекбоксы, аудит; нужна живая сборка на сервере |
| Поиск < 1 с | Elasticsearch-индексация письма при доставке есть в коде; замер latency на боевом объёме — runtime-гейт |
| Алертинг email | Цепочка закодирована, нужна живая проверка |
| Chrome 109 / адаптив | Да, сборка проверена (legacy-бандл) |
