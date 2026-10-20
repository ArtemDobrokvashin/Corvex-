# 1. Детальная диаграмма архитектуры

> К пункту 1 формата вывода ТЗ. Зафиксированные выборы там, где ТЗ допускало «или»,
> вынесены в таблицу ADR в конце файла — каждый выбор обоснован именно для масштаба
> «один сервер, до 1000 сотрудников, Windows-хост».

## 1.1. Системная диаграмма (C4, уровень контейнеров)

```mermaid
flowchart TB
    subgraph Clients["Клиенты"]
        WEB["Веб-клиент React<br/>Chrome 109 / Яндекс / Edge / Firefox<br/>desktop · планшет · смартфон"]
        DESK["Outlook · Thunderbird<br/>IMAP/POP3/SMTP + TLS"]
        EXT["Внешние почтовые<br/>серверы Интернета"]
    end

    subgraph Edge["Периметр (сеть edge)"]
        NGX["Nginx<br/>HTTPS 443 · TLS 1.2+ · HSTS"]
    end

    subgraph App["Микросервисы Corvex (сеть app)"]
        GW["web-gateway :8080<br/>Spring Cloud Gateway<br/>JWT · rate limit · маршруты"]
        AUTH["auth-service :8081<br/>локальные учётки + AD/LDAP<br/>JWT RS256 · TOTP 2FA"]
        ADMIN["admin-service :8090<br/>пользователи · RBAC · аудит"]
        STOR["mail-storage-service :8085<br/>папки · письма · вложения · поиск"]
        SMTP["mail-smtp-service<br/>25 / 587 (SubEtha SMTP)"]
        IMAP["mail-imap-service<br/>143 / 993 (Apache James)"]
        POP3["mail-pop3-service<br/>110 / 995 (Apache James)"]
        SPAM["mail-spam-service :8086<br/>обёртка над spamd"]
        AV["mail-antivirus-service :8087<br/>обёртка над clamd"]
        CHAT["chat-service :8088<br/>WebSocket + STOMP"]
        CFILE["chat-file-service :8089<br/>файлы/голос/видео чата"]
        NOTIF["notification-service :8091<br/>email-алерты"]
    end

    subgraph Data["Данные и внешние движки (сеть data, без портов наружу)"]
        PG[("PostgreSQL 16<br/>auth_db · admin_db<br/>mail_db · chat_db")]
        REDIS[("Redis 7<br/>сессии · presence<br/>rate limit · blacklist")]
        MQ{{"RabbitMQ 4.3<br/>AMQP + STOMP-реле"}}
        ES[("Elasticsearch 8<br/>поиск по почте и чату")]
        FS[("Тома Docker<br/>Maildir · вложения CAS<br/>файлы чата")]
        SPAMD["SpamAssassin (spamd)"]
        CLAM["ClamAV (clamd)"]
        AD["Active Directory<br/>(LDAPS 636) компании"]
    end

    subgraph Obs["Наблюдаемость (профиль monitoring)"]
        PROM["Prometheus + Alertmanager"]
        GRAF["Grafana"]
        LOKI["Loki + Promtail (логи)"]
    end

    WEB -->|"HTTPS /api, WSS /ws"| NGX --> GW
    GW --> AUTH & ADMIN & STOR & CHAT & CFILE
    DESK --> IMAP & POP3 & SMTP
    EXT -->|"SMTP 25"| SMTP
    SMTP -->|"mail.inbound.received"| MQ
    MQ --> SPAM --> SPAMD
    MQ --> AV --> CLAM
    MQ --> STOR
    MQ --> NOTIF
    CHAT -->|"STOMP broker relay 61613"| MQ
    AUTH --> PG & REDIS
    AUTH -.->|"bind + синхронизация"| AD
    ADMIN --> PG
    STOR --> PG & ES & FS
    CHAT --> PG & REDIS & ES
    IMAP & POP3 --> STOR
    IMAP & POP3 & SMTP -.->|"проверка пароля / app-password"| AUTH
    App -.->|"метрики /actuator/prometheus"| PROM
    App -.->|"JSON-логи stdout"| LOKI
    PROM --> GRAF
    PROM -->|"webhook алертов"| NOTIF
```

## 1.2. Конвейер входящего письма (самая ответственная цепочка)

Принцип: SMTP-сессия короткая, вся дорогая работа — асинхронная; письмо не теряется
при падении любого звена после ответа `250 OK` (сырой .eml уже записан, события — в
надёжных очередях RabbitMQ с DLQ).

```mermaid
sequenceDiagram
    participant S as Внешний SMTP
    participant SMTP as mail-smtp-service
    participant ST as mail-storage-service
    participant MQ as RabbitMQ
    participant SP as mail-spam-service
    participant AV as mail-antivirus-service
    participant ES as Elasticsearch
    participant IM as mail-imap-service

    S->>SMTP: EHLO, MAIL FROM, RCPT TO (только локальные домены!)
    S->>SMTP: DATA (.eml, ≤ лимита размера)
    SMTP->>ST: сохранить сырой .eml (internal API)
    SMTP->>MQ: mail.inbound.received
    SMTP-->>S: 250 OK (id = rawRef для трассировки)
    MQ->>SP: проверить (SPAMC → spamd)
    SP-->>MQ: mail.spam.checked (score)
    MQ->>AV: сканировать вложения (INSTREAM → clamd)
    AV-->>MQ: mail.av.checked (CLEAN/INFECTED)
    MQ->>ST: доставить: правила пользователя → папка<br/>(SPAM по score, INBOX по умолчанию), квота, автоответ
    ST->>ST: Maildir tmp→new + метаданные в PostgreSQL + CAS вложений
    ST->>ES: проиндексировать (тема/тело/вложения)
    ST->>MQ: mail.delivered
    MQ->>IM: notifyNewMail → IDLE-клиенты получают EXISTS
    MQ->>CHATNS: (опц.) уведомление в единый центр уведомлений веба
```

Режимы отказа в конвейере (зафиксированы в коде клиентов):

| Звено | При недоступности | Обоснование |
|---|---|---|
| SpamAssassin | **fail-open**: доставка в INBOX, `spam_score=NULL`, заголовок `X-Corvex-Spam-Checked: no`, алерт | потеря деловой почты хуже, чем лишний спам на время восстановления |
| ClamAV | **fail-closed по вложению**: письмо доставляется, вложение заблокировано (`AV_STATUS=ERROR`), повторная проверка фоновой задачей | инфицированный файл недопустим, но и письмо терять не нужно |
| Elasticsearch | доставка не блокируется, индексация — повтор из очереди; поиск временно отстаёт | индекс — производные данные, перестраивается из БД |
| PostgreSQL | SMTP отвечает внешнему серверу `451` (временная ошибка) **до** записи сырого письма | отправитель сам повторит доставку позже — стандартное поведение SMTP |

## 1.3. Исходящая почта и доставляемость

```mermaid
sequenceDiagram
    participant U as Веб-клиент / Outlook (587)
    participant ST as mail-storage-service
    participant MQ as RabbitMQ
    participant SMTP as mail-smtp-service (relay-воркер)
    participant DNS as DNS получателя (MX)
    participant R as Сервер получателя

    U->>ST: POST /mail/messages/send (или SMTP AUTH на 587)
    ST->>ST: проверки: право MAIL_SEND_EXTERNAL по доменам, квота, размер, AV вложений
    ST->>ST: копия в «Отправленные»
    ST->>MQ: mail.outbound.queued
    MQ->>SMTP: забрать задание
    SMTP->>SMTP: DKIM-подпись (2048 бит, селектор corvex1)
    SMTP->>DNS: MX-запись домена
    SMTP->>R: SMTP + STARTTLS (обязателен, если предлагается)
    alt 250 OK
        SMTP->>MQ: mail.outbound.sent
    else 4xx (временно)
        SMTP->>MQ: повтор с экспоненциальной паузой: 5м → 15м → 1ч → 4ч … до 72 ч
    else 5xx (постоянно)
        SMTP->>ST: сгенерировать DSN (bounce) отправителю в INBOX
    end
```

Доставляемость обеспечивается не кодом, а инфраструктурой — чек-лист в инструкции по
развёртыванию ([08-deployment-windows-server.md](08-deployment-windows-server.md), шаг 7):
публичный статический IP без «серых» NAT, PTR/rDNS = `mail.<домен>`, SPF `-all` после
пилота, DKIM-подпись, DMARC `p=none` → `quarantine` → `reject` по мере накопления
отчётов, исходящий 25 порт, разблокированный провайдером.

## 1.4. Аутентификация (веб и почтовые протоколы — одна identity)

```mermaid
sequenceDiagram
    participant B as Браузер
    participant GW as web-gateway
    participant A as auth-service
    participant AD as Active Directory
    participant RD as Redis
    participant SVC as Любой микросервис

    B->>GW: POST /auth/login {username, password}
    GW->>A: проксирование (+ rate limit 10/мин по IP)
    alt пользователь AD
        A->>AD: LDAP bind (LDAPS), чтение групп/атрибутов
    else локальный
        A->>A: Argon2id-проверка пароля, счётчик попыток, lockout
    end
    opt включена 2FA
        A-->>B: 202 {mfaTicket}
        B->>A: POST /auth/mfa/verify {TOTP-код}
    end
    A->>RD: refresh-сессия (SHA-256 токена, ротация)
    A-->>B: access JWT RS256 на 15 мин (claims: sub, permissions, jti)<br/>Set-Cookie refresh: HttpOnly, Secure, SameSite=Strict
    B->>GW: запросы с Bearer access
    GW->>GW: проверка подписи по JWKS (кэш ключей)
    GW->>SVC: запрос + тот же Bearer
    SVC->>SVC: повторная проверка подписи и прав (defense in depth)
```

Для Outlook/Thunderbird: IMAP/SMTP аутентификация ходит в internal API auth-service;
при включённой 2FA пользователь создаёт **пароль приложения** (app-password) в вебе —
основной пароль с TOTP в почтовых протоколах неприменим физически.

## 1.5. Чат реального времени

STOMP поверх WebSocket (SockJS-fallback для старых прокси), брокер-реле — RabbitMQ
STOMP plugin: второй экземпляр chat-service добавляется без потери сообщений между
нодами. Присутствие (`online/away/dnd/offline`), «печатает…» и счётчики непрочитанных —
Redis с TTL; история и поиск — PostgreSQL + Elasticsearch. Детали адресации — в
[03-api-contracts.md](03-api-contracts.md#websocketstomp).

## 1.6. Сетевая изоляция и порты

| Порт наружу | Назначение | Кто слушает |
|---|---|---|
| 443/tcp | веб-интерфейс, API, WSS | nginx → gateway/frontend |
| 80/tcp | редирект на HTTPS + ACME challenge | nginx |
| 25/tcp | приём почты от внешних серверов | mail-smtp-service |
| 587/tcp | отправка своими клиентами (SMTP AUTH + STARTTLS обязательны) | mail-smtp-service |
| 993 / 143 | IMAPS / IMAP+STARTTLS | mail-imap-service |
| 995 / 110 | POP3S / POP3+STLS | mail-pop3-service |

PostgreSQL, Redis, RabbitMQ, Elasticsearch — сеть `data` с `internal: true`, порты
на хост не публикуются вообще. Grafana доступна только через nginx с allowlist подсети
администраторов. Внутри контейнеров сервисы работают от non-root и на
непривилегированных портах (25→2525 и т.п.), проброс делает Docker.

## 1.7. Архитектурные решения (ADR) — выбор из альтернатив ТЗ

| # | Вопрос | Решение | Почему |
|---|---|---|---|
| ADR-01 | RabbitMQ или Kafka | **RabbitMQ** | 1000 пользователей ≈ единицы писем/сек в пике; нужны routing, DLQ, подтверждения и STOMP-реле чата из одного брокера. Kafka оправдана при потоковой аналитике, которой в ТЗ нет, а операционно тяжелее на одном сервере |
| ADR-02 | ELK или Loki для логов | **Loki + Grafana**, ES — только для поиска | второй тяжёлый ES-кластер ради логов на 16–32 ГБ RAM не помещается рядом с поисковым; Loki индексирует только метки |
| ADR-03 | Почтовые протоколы | **SubEtha SMTP** (SMTP), **Apache James protocol libs** (IMAP/POP3) за портами-адаптерами | ТЗ прямо запрещает писать RFC-парсеры с нуля; порты (`ImapServerPort`) позволяют заменить движок без переписывания хранилища |
| ADR-04 | Хранение писем | Maildir (.eml) + метаданные PostgreSQL + CAS вложений по SHA-256 | Maildir = атомарность и бесплатная совместимость с инструментами миграции/восстановления; CAS даёт дедупликацию вложений между ящиками |
| ADR-05 | Состояние фронтенда | **Zustand** | меньше бандл и бойлерплейта, чем Redux Toolkit; при 3 модулях приложения этого достаточно, серверный кэш не дублируем в сторе |
| ADR-06 | JWT | RS256, access 15 мин + вращающийся opaque refresh в HttpOnly cookie | сервисы валидируют токены локально по JWKS без сетевых вызовов; цена — отзыв прав в пределах 15 мин (документируется админу) |
| ADR-07 | Docker на Windows Server | Прод: **Linux-VM в Hyper-V**; пилот допустим на Docker Desktop | Docker Desktop официально не поддерживается на Windows Server и требует платной подписки для крупных компаний; Linux-контейнеры на «голом» Windows Server продакшен-пути не имеют. Подробно — docs/08, шаг 2 |
| ADR-08 | Миграции БД | **Flyway**, SQL-миграции в каждом сервисе | SQL прозрачнее для DBA-ревью, чем Liquibase XML; одна БД — один владелец-сервис |
| ADR-09 | Стили фронтенда | Tailwind CSS 3 + Autoprefixer (targets Chrome ≥109) | Tailwind 4 требует современных CSS-возможностей; третья версия стабильно работает с legacy-сборкой |
| ADR-10 | Масштабирование | вертикальное + реплики stateless-сервисов за gateway | целевая нагрузка (1000 одновременных) закрывается одним сервером рекомендуемой конфигурации; горизонтальный рост заложен в stateless-дизайн и внешний STOMP-брокер, но Kubernetes сознательно вне скоупа |

## 1.8. Расчёт нагрузки (обоснование нефункциональных требований §11.2)

- 1000 сотрудников, ~40 писем/чел/день → 40 000 писем/день, пик ≈ 5 писем/сек —
  конвейер spam→AV→доставка проходит на одном воркере каждого типа с запасом ×10.
- Чат: 1000 одновременных WS-соединений — один экземпляр chat-service (STOMP-реле
  выносит рассылку в RabbitMQ); 500 сообщений/мин в пике — тривиально для PostgreSQL.
- Хранилище: 1000 ящиков × 5 ГБ квоты = 5 ТБ логически; реальное заполнение за первый
  год ≈ 15–25% квот + дедупликация вложений (типично −20–30%) → том 1 ТБ из
  рекомендуемой конфигурации покрывает 1,5–2 года с контролем квот админом.
- Поиск < 1 с: индекс ES по метаданным и телам (без бинарных вложений, текст вложений
  извлекается Tika-подобным экстрактором на этапе индексации), один шард на индекс
  помесячно — при объёмах выше пятилетний горизонт не нужен.
