# 3. API-контракты

> К пункту 3 формата вывода. Полные OpenAPI-спецификации: [`docs/api/`](api/)
> (`auth-api.yaml`, `mail-api.yaml`, `chat-api.yaml`, `admin-api.yaml`) — импортируются
> в Swagger UI/Postman; этот документ — карта, принципы и не-REST контракты.

## 3.1. Общие соглашения

- База: `https://mail.<домен>/api/v1`, снаружи виден только gateway.
- Авторизация: `Authorization: Bearer <JWT RS256>`; права — claim `permissions`
  (коды из `Permission`), каждый сервис проверяет подписи и права сам.
- Ошибки — RFC 7807 Problem Details: `{type, title, status, detail, requestId}`.
- Пагинация длинных лент (письма, сообщения чата) — **курсорная** (не OFFSET):
  список из 200 тыс. писем не деградирует на глубоких страницах.
- Идемпотентность там, где сеть может повторить запрос: реакции (PUT),
  отправка сообщений чата (`clientMessageId`), флаги писем (PATCH полным состоянием).
- Лимиты: login 10/мин по IP, API 50 rps burst 100 на пользователя (gateway, Redis).

## 3.2. Карта REST по доменам (детали — в YAML)

| Домен | Префикс | Главные ресурсы |
|---|---|---|
| Auth | `/auth/**` | login, MFA verify, refresh, logout, me (+права/квота), TOTP setup/confirm, app-passwords, JWKS |
| Почта | `/mail/**` | folders (CRUD+счётчики), messages (список/чтение/флаги/bulk-move/send), drafts, attachments (upload/download), search, rules, contacts, signatures, vacation |
| Чат | `/chat/**`, `/files` | rooms, direct-диалоги, history, edit/delete, reactions, pin, read, search, presence, загрузка файлов |
| Админ | `/admin/**` | users (CRUD/block/groups), groups+permissions, settings, audit, analytics/activity, system/health, backup/run |

## 3.3. WebSocket/STOMP (контракт чата)

```
CONNECT  /ws/chat                      заголовок Authorization: Bearer <JWT> (или SockJS)
SUBSCRIBE /topic/rooms/{roomId}        сообщения, правки, удаления, реакции комнаты
SUBSCRIBE /topic/rooms/{roomId}/typing индикаторы «печатает…» (эфемерно, без БД)
SUBSCRIBE /user/queue/notifications    упоминания @, приглашения, личные события
SUBSCRIBE /topic/presence              online/away/dnd/offline
SEND     /app/rooms/{roomId}/send      {bodyMarkdown, threadRootId?, clientMessageId}
SEND     /app/rooms/{roomId}/typing    {}
```

Каждое сообщение авторизуется сервером (членство в комнате + `CHAT_WRITE`, read-only
системных каналов), а не только CONNECT. Доставка офлайн-участникам: unread-счётчики
в БД + browser Notification API при следующем входе; мобильный push вне скоупа ТЗ.

## 3.4. События RabbitMQ (обменник `corvex.mail` — topic; `corvex.events` — topic)

| Routing key | Издатель → Потребители | Содержимое/гарантии |
|---|---|---|
| `mail.inbound.received` | smtp → spam | `rawRef, mailFrom, recipients, size`; очередь durable, DLQ после 5 попыток |
| `mail.spam.checked` | spam → av | сырой ref + score/threshold/checked |
| `mail.av.checked` | av → storage | статус по каждому вложению |
| `mail.delivered` | storage → imap, web-уведомления | mailbox/folder/uid — триггер IDLE |
| `mail.outbound.queued` | storage/imap-submit → smtp-воркер | задание отправки, DKIM на воркере |
| `mail.outbound.sent` / `.bounced` | smtp → storage | финальный статус, генерация DSN |
| `chat.message.created` | chat → storage-индексатор ES | идемпотентная индексация по id |
| `corvex.alerts` (очередь) | notification webhook ← Alertmanager | алерты мониторинга → email админам |
| `admin.settings.changed` | admin → все | инвалидация кэшей настроек (лимиты, домены) |
| `admin.user.blocked` | admin → auth, chat | отзыв сессий и отключение WS |

Потребители идемпотентны (повторная доставка неизбежна в AMQP — at-least-once),
ядовитые сообщения после 5 попыток уходят в DLQ с алертом в мониторинг.

## 3.5. Внутренние (internal) API

Не пробрасываются через gateway; доступны только из сети `app`, защищены mTLS-сеткой
Docker-сети и служебным JWT с audience `internal`:

- `auth-service  POST /internal/verify-password {username, password|appPassword}` —
  для IMAP/POP3/SMTP аутентификации;
- `mail-storage-service POST /internal/raw` — приём сырого .eml от smtp-сервиса;
- `notification-service POST /internal/alerts/webhook` — вебхук Alertmanager;
- `admin-service → auth-service /internal/users/**` — управление учётками из админки.
