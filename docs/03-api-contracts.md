# 3. API-контракты

> К пункту 3 формата вывода. Полные OpenAPI-спецификации: [`docs/api/`](api/)
> (`auth-api.yaml`, `mail-api.yaml`, `chat-api.yaml`, `admin-api.yaml`) — импортируются
> в Swagger UI/Postman; этот документ — карта, принципы и не-REST контракты.

## 3.1. Общие соглашения

- База: `https://mail.<домен>/api/v1`, снаружи виден только gateway.
- Авторизация: `Authorization: Bearer <JWT RS256>`; группы — claim `groups`, права —
  claim `permissions` (коды из `Permission`), каждый сервис проверяет подпись и права сам.
- Локаль публичных ошибок: `Accept-Language: ru` или `en`; auth/admin локализуют
  известные `detail` на HTTP-границе, неизвестная диагностика не маскируется.
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
| Чат | `/chat/**`, `/files/**` | rooms, direct-диалоги, members, history/send, edit/delete, reactions, pin, read, search, directory, presence, загрузка/скачивание файлов |
| Админ | `/admin/**` | users (CRUD/block/groups), groups+permissions, settings, audit, analytics/activity, system/health, backup/run |

Фактически реализованный на Stage 2 срез почты существенно уже целевой карты выше:
`GET /mail/folders`, `GET /mail/folders/{id}/messages?cursorUid=&limit=`,
`GET /mail/messages/{id}`, `PATCH /mail/messages/{id}/flags`,
`POST /mail/messages/{id}/move {targetFolderId}` и
`POST /mail/messages/send {to, cc, bcc, subject, bodyText, bodyHtml?, attachments[], saveToSent}`.
Черновики, отдельная загрузка/выгрузка вложений, поиск, правила, контакты, подписи и
vacation пока остаются целевым контрактом следующих этапов.

Фактически реализованный Stage 4 срез чата совпадает с `docs/api/chat-api.yaml`:
`GET/POST /chat/rooms`, `POST /chat/rooms/direct/{userId}`,
`GET/POST /chat/rooms/{roomId}/members`, `DELETE /chat/rooms/{roomId}/members/me`,
`GET/POST /chat/rooms/{roomId}/messages`, `PATCH/DELETE /chat/messages/{messageId}`,
`PUT /chat/messages/{messageId}/reactions`, `PUT/DELETE /chat/messages/{messageId}/pin`,
`POST /chat/rooms/{roomId}/read`, `GET /chat/search`, `GET /chat/directory`,
`GET/PUT /chat/presence`, а также `POST /files` и авторизованный blob-download
`GET /files/{fileRef}`. Удаление, реакции и pin возвращают обновлённый `MessageView`
со статусом `200`, а не `204`; удалённое сообщение маскирует тело и `fileRef`.

## 3.3. WebSocket/STOMP (контракт чата)

```
CONNECT  /ws/chat                   заголовок Authorization: Bearer <JWT> (WebSocket или SockJS)
SUBSCRIBE /topic/rooms.{roomId}     ChatEventView комнаты: сообщения, правки, удаления,
                                    реакции/pin как обновления, MESSAGE_READ и TYPING
SUBSCRIBE /user/queue/notifications личные MENTION-события
SUBSCRIBE /user/queue/errors        {status, detail} для ошибок STOMP-обработчиков
SEND     /app/rooms/{roomId}/send   {bodyMarkdown, threadRootId?, clientMessageId,
                                    kind?, fileRef?, mentionedUserIds[]}
SEND     /app/rooms/{roomId}/read   {lastReadMessageId}
SEND     /app/rooms/{roomId}/typing {}
```

Bearer JWT обязателен на STOMP `CONNECT` (HTTP/SockJS handshake сам по себе не даёт доступа),
а членство и право записи перепроверяются на `SUBSCRIBE`/`SEND`; произвольные topic/destination
запрещены. `ChatEventView` имеет форму
`{type, roomId, message, actorId, messageId, occurredAt}`, где `type` — один из
`MESSAGE_CREATED`, `MESSAGE_UPDATED`, `MESSAGE_DELETED`, `MESSAGE_READ`, `TYPING`, `MENTION`.
Персистентные события комнаты публикуются после коммита PostgreSQL; `TYPING` эфемерен.
Повторный `clientMessageId` того же автора в той же комнате возвращает исходное сообщение,
поэтому клиент может безопасно перейти со STOMP на REST-fallback при обрыве соединения.
Presence не раздаётся отдельным STOMP-topic: текущий контракт — `GET/PUT /chat/presence`.
Доставка офлайн-участникам опирается на unread/mention-счётчики при следующем входе;
мобильный push вне скоупа ТЗ.

## 3.4. События RabbitMQ (обменник `corvex.mail` — topic; `corvex.events` — topic)

| Routing key | Издатель → Потребители | Содержимое/гарантии |
|---|---|---|
| `mail.inbound.received` | smtp → spam | `rawRef, mailFrom, recipients, size`; очередь durable, DLQ после 5 попыток |
| `mail.spam.checked` | spam → av | сырой ref + score/threshold/checked |
| `mail.av.checked` | av → storage | статус по каждому вложению |
| `mail.delivered` | storage → imap, web-уведомления | mailbox/folder/uid — триггер IDLE |
| `mail.outbound.queued` | storage / SMTP submission → smtp-воркер | задание отправки; DKIM, MX, TTL-retry до 72 часов на воркере |
| `mail.outbound.sent` / `.bounced` | smtp → storage | финальный статус, генерация DSN |
| `chat.message.created` | целевой outbox chat → ES-индексатор | в текущем Stage 4 поиск выполняется по PostgreSQL только в комнатах пользователя; RabbitMQ используется как STOMP broker relay, а не как источник истины истории |
| `corvex.alerts` (очередь) | notification webhook ← Alertmanager | алерты мониторинга → email админам |
| `admin.settings.changed` | admin → все | инвалидация кэшей настроек (лимиты, домены) |
| `admin.user.blocked` | admin → auth, chat | отзыв сессий и отключение WS |

Потребители идемпотентны (повторная доставка неизбежна в AMQP — at-least-once),
ядовитые сообщения после 5 попыток уходят в DLQ с алертом в мониторинг.

## 3.5. Внутренние (internal) API

Не пробрасываются через gateway и доступны только из сети `app`. Реализованные
auth/admin/chat `/internal/**` дополнительно требуют заголовок
`X-Corvex-Internal-Token` с секретом `CORVEX_INTERNAL_API_TOKEN`; без секрета
сервис отвечает `503` (fail closed), с неверным — `401`. Отдельные credentials
per caller или mTLS остаются усилением Stage 5, а не текущим состоянием:

- `auth-service  POST /internal/verify-password {username, password|appPassword}` —
  для IMAP/POP3/SMTP аутентификации;
- `auth-service  GET /internal/users/by-email?email=...` — Stage 2 разрешение получателя
  в владельца ящика (id/status/quota) для SMTP RCPT-проверки и доставки storage;
- `auth-service  GET /internal/users/{id}` и
  `GET /internal/users?status=ACTIVE&page=&size=&q=` — минимальный корпоративный
  каталог и проверка активности участников для chat-service;
- `chat-service  GET /internal/chat/rooms/{roomId}/members/{userId}` — только факт
  актуального членства, роль, mute и read-only флаг комнаты для chat-file-service;
  содержимое сообщений через этот endpoint не отдаётся;
- `mail-storage-service POST /internal/raw` — потоковый приём сырого .eml от smtp-сервиса
  в durable spool; `GET /internal/raw/{rawRef}` читают spam/AV, `DELETE` чистит spool
  после доставки или неудачной публикации события;
- `mail-storage-service /internal/protocol/users/{userId}/...` — протокольный API этапа 3
  для IMAP/POP3 (тот же fail-closed токен): `GET/POST /folders`, `GET /folders/{id}/status`,
  `PATCH/DELETE /folders/{id}`, `GET /folders/{id}/messages?afterUid=&limit=`,
  `GET /folders/{id}/messages/{uid}/raw` и `GET /messages/{id}/raw` (сырой .eml),
  `PATCH .../messages/{uid}/flags` (частичные флаги, `null` = не трогать),
  `POST /folders/{id}/expunge` (пустой список = все `\Deleted`),
  `POST /folders/{id}/messages` (APPEND: octet-stream + `X-Corvex-Internal-Date`/`X-Corvex-Flags`,
  ответ — `messageId + uid + modseq`),
  `POST /folders/{id}/copy` (COPY/MOVE, ответ — пары UID), `DELETE /messages/{id}`
  (POP3 DELE на QUIT), `GET /quota`. `FolderInfo` несёт `uidNext/uidValidity/highestModseq`,
  `MessageMeta` — `uid/modseq`: MODSEQ (миграция `V3__imap_modseq.sql`) выделяется из той же
  заблокированной строки папки, что UID, и двигается на APPEND/COPY/MOVE/флагах/expunge.
  `PATCH /folders/{id}` принимает `name + parentId/moveToRoot`, поэтому IMAP RENAME умеет
  переносить папку в другую иерархию без переименования каталога Maildir при том же имени листа.
  Каждый объект сверяется по `owner_id` из пути:
  чужая папка/письмо — `404`; системные папки нельзя удалить/переименовать (`409`);
  APPEND сверх квоты владельца — `507`;
- `notification-service POST /internal/alerts/webhook` — вебхук Alertmanager;
- `admin-service → auth-service /internal/users/**` — управление учётками из админки.
