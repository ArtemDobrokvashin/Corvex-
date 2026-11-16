# API

Машиночитаемые спецификации лежат в [`api/`](api/): `auth-api.yaml`, `mail-api.yaml`,
`chat-api.yaml`, `admin-api.yaml` — их можно импортировать в Swagger UI или Postman.
Этот документ — карта, общие соглашения и контракты, которых нет в OpenAPI: STOMP,
события RabbitMQ и внутренние API.

## 1. Общие соглашения

- База: `https://mail.<домен>/api/v1`. Снаружи виден только gateway.
- Авторизация: `Authorization: Bearer <JWT RS256>`. Группы — claim `groups`, права —
  claim `permissions`; каждый сервис проверяет подпись и права сам.
- Ошибки — RFC 7807 Problem Details: `{type, title, status, detail, requestId}`.
  Язык `detail` выбирается по `Accept-Language: ru|en` для известных ошибок auth/admin;
  неизвестная диагностика не маскируется.
- Пагинация длинных лент — курсорная, не OFFSET: список из 200 тысяч писем не деградирует
  на глубоких страницах.
- Идемпотентность там, где сеть может повторить запрос: реакции (`PUT`), отправка
  сообщений чата (`clientMessageId`), флаги писем (`PATCH` полным состоянием).
- Лимиты: вход 10/мин по IP, API 50 rps burst 100 на пользователя (gateway, Redis).

## 2. Карта REST

| Домен | Префикс | Ресурсы |
|---|---|---|
| Auth | `/auth/**` | login, MFA verify, refresh, logout, me (права и квота), TOTP, app-passwords, JWKS |
| Почта | `/mail/**` | папки, письма, флаги, отправка (вложения — base64 в теле), черновики, вложения к скачиванию, адресная книга, внешние ящики и импорт архива |
| Календарь | `/calendar/events` | события: список, создание, изменение, удаление |
| Чат | `/chat/**`, `/files/**` | комнаты, личные диалоги, участники, история, реакции, закрепления, прочтения, поиск, presence, файлы |
| Файлы (диск) | `/drive/**` | папки, файлы, квота, корзина, ссылки |
| Админ | `/admin/**` | пользователи, группы и права, настройки, аудит, аналитика, health, backup |

Правила автосортировки, подписи и автоответ «отпуск» пока не реализованы: таблицы в схеме БД
есть как заготовка, но контроллеров и интерфейса нет. В [`api/mail-api.yaml`](api/mail-api.yaml)
такие операции помечены `x-corvex-status: planned`. Поиск в веб-почте фильтрует уже
загруженный список на клиенте; полнотекстовый `/mail/search` на Elasticsearch не реализован,
хотя индекс наполняется.

Адресная книга реализована: `GET/POST /mail/contacts`, `PUT/DELETE /mail/contacts/{id}`,
`POST /mail/contacts/import` (multipart vCard/CSV) и `GET /mail/contacts/export`.
Право — `MAIL_READ_OWN`: книга личная, доступа администратора к чужим контактам нет.

## 3. Что реализовано в webmail

`GET /mail/folders`, `POST /mail/folders {name, parentId?}` (право `MAIL_MANAGE_FOLDERS`),
`GET /mail/folders/{id}/messages?cursorUid=&limit=`, `GET /mail/messages/{id}`,
`GET /mail/messages/{id}/attachments/{partIndex}`, `PATCH /mail/messages/{id}/flags`,
`POST /mail/messages/{id}/move {targetFolderId}`,
`POST /mail/messages/send {to, cc, bcc, subject, bodyText, bodyHtml?, attachments[], saveToSent}`.

Отправка публикует письма в очередь доставки; копия в Sent сохраняется отдельно, поэтому
`sentMessageId` может быть `null` (например, при превышении квоты). У отправки **нет**
ключа идемпотентности: тайм-аут после публикации оставляет неоднозначный результат, и
автоматический повтор может создать дубликат. Успешный ответ означает приём в очередь, а
не подтверждённую доставку.

Maildir использует плоские санитизированные имена каталогов, а не идентификаторы папок:
разные имена могут дать один путь (например, кириллические имена одинаковой длины).
Создание, переименование и удаление сериализуются на уровне владельца и возвращают `409`
при файловом конфликте. Удаление или переименование папки с зарезервированным либо
недопустимым сегментом блокируется. Каталог ящика тоже строится из санитизированного
email, поэтому `user+tag@example.com` и `user_tag@example.com` могут делить путь; файловые
операции и транзакции БД не являются единой атомарной операцией. Это известное
ограничение, а не скрытый компромисс.

## 4. Личный диск

Все пути, кроме `/shared/**`, требуют JWT и работают только с файлами текущего
пользователя: параметра «владелец» в API нет, подставить чужой идентификатор нельзя.

| Метод и путь | Назначение |
|---|---|
| `GET /drive?parentId=&view=files\|trash&q=` | содержимое папки (без `parentId` — корень) или корзина; `q` — поиск по имени |
| `GET /drive/usage` | `{usedBytes, quotaBytes}` — отдельно от квоты почты |
| `GET /drive/folders` | плоский список всех папок без корзины — для выбора назначения при перемещении |
| `POST /drive/folders {parentId, name}` | создать папку |
| `POST /drive/files?parentId=` (multipart) | загрузка; AV fail-closed, квота проверяется до публикации blob |
| `PATCH /drive/{id}/name {name}` | переименовать |
| `POST /drive/{id}/move {parentId}` | переместить (папку нельзя переместить внутрь себя) |
| `POST /drive/{id}/trash` / `restore` | корзина и восстановление поддерева |
| `DELETE /drive/{id}` | окончательное удаление; только для объекта в корзине |
| `GET /drive/{id}/content` | скачивание владельцем |
| `GET/POST /drive/{id}/shares`, `DELETE /drive/shares/{shareId}` | список, создание и отзыв ссылок |
| `GET /drive/shared/{token}` | публичное скачивание по ссылке, без JWT |

Модель ссылок — как в Google Drive: файл приватный по умолчанию; ссылка создаётся
отдельным действием, `POST` возвращает токен **один раз** (в хранилище только SHA-256),
`expiresInDays` необязателен; отзыв и истечение проверяются на каждый запрос, отозванная
ссылка сразу отвечает `404`. Ответы для несуществующей, отозванной и просроченной ссылки
неразличимы, чтобы токены нельзя было перебирать. Публичный ответ отдаётся как
`attachment` с `nosniff`, `CSP: default-src 'none'; sandbox`, `Referrer-Policy:
no-referrer` и `no-store`. Папки по ссылке не публикуются.

## 5. STOMP (чат)

```
CONNECT  /ws/chat                   Authorization: Bearer <JWT>
SUBSCRIBE /topic/rooms.{roomId}     сообщения, правки, удаления, реакции, pin,
                                    MESSAGE_READ, TYPING
SUBSCRIBE /user/queue/notifications упоминания
SUBSCRIBE /user/queue/errors        {status, detail}
SEND     /app/rooms/{roomId}/send   {bodyMarkdown, threadRootId?, clientMessageId,
                                    kind?, fileRef?, mentionedUserIds[]}
SEND     /app/rooms/{roomId}/read   {lastReadMessageId}
SEND     /app/rooms/{roomId}/typing {}
```

Bearer обязателен на `CONNECT`: HTTP/SockJS handshake сам по себе доступа не даёт.
Членство и право записи перепроверяются на `SUBSCRIBE` и `SEND`, произвольные topic
запрещены. Событие имеет вид `{type, roomId, message, actorId, messageId, occurredAt}`,
где `type` — `MESSAGE_CREATED | MESSAGE_UPDATED | MESSAGE_DELETED | MESSAGE_READ | TYPING |
MENTION`. Персистентные события публикуются после коммита PostgreSQL, `TYPING` эфемерен.
Повторный `clientMessageId` того же автора возвращает исходное сообщение, поэтому клиент
может безопасно перейти на REST-fallback при обрыве.

## 6. События RabbitMQ

| Routing key | Издатель → потребители | Гарантии |
|---|---|---|
| `mail.inbound.received` | smtp → spam | `rawRef, mailFrom, recipients, size`; durable, DLQ после 5 попыток |
| `mail.spam.checked` | spam → av | rawRef + score/threshold/checked |
| `mail.av.checked` | av → storage | статус по каждому вложению |
| `mail.delivered` | storage → imap, уведомления | mailbox/folder/uid, триггер IDLE |
| `mail.outbound.queued` | storage/submission → smtp | задание отправки; DKIM, MX, TTL-retry до 72 часов |
| `mail.outbound.sent` / `.bounced` | smtp → storage | финальный статус, генерация DSN |
| `admin.settings.changed` | admin → все | инвалидация кэшей настроек |
| `admin.user.blocked` | admin → auth, chat | отзыв сессий, отключение WebSocket |

Потребители идемпотентны: AMQP даёт at-least-once. «Ядовитые» сообщения после 5 попыток
уходят в DLQ с алертом.

## 7. Внутренние API

Не маршрутизируются через gateway и доступны только из сети `app`. Требуют заголовок
`X-Corvex-Internal-Token` с секретом `CORVEX_INTERNAL_API_TOKEN`: без секрета `503`
(fail-closed), с неверным — `401`. Отдельные credentials на вызывающего и mTLS — усиление
следующего этапа, а не текущее состояние.

- `auth-service POST /internal/verify-password` — вход по паролю или app-password для
  IMAP/POP3/SMTP;
- `auth-service GET /internal/users/by-email` — разрешение получателя в владельца ящика;
- `auth-service GET /internal/users/{id}`, `GET /internal/users?status=ACTIVE` — каталог;
- `chat-service GET /internal/chat/rooms/{roomId}/members/{userId}` — факт членства и
  право записи для chat-file-service; содержимое сообщений не отдаётся;
- `mail-storage-service POST /internal/raw` — потоковый приём `.eml` в durable spool;
  `GET /internal/raw/{rawRef}` читают spam/AV, `DELETE` чистит spool после доставки;
- `mail-storage-service /internal/protocol/users/{userId}/...` — протокольный API для
  IMAP/POP3: папки, `UID/NEXT/UIDVALIDITY/MODSEQ`, флаги, `APPEND`, `COPY/MOVE`, `expunge`,
  `quota`. Чужой объект — `404`, системную папку удалить нельзя (`409`), APPEND сверх
  квоты — `507`;
- `notification-service POST /internal/alerts/webhook` — приём вебхука Alertmanager;
- `notification-service POST /internal/calendar/reminders` — отправка напоминания;
- `admin-service → auth-service /internal/users/**` — управление учётками;
- `admin-service → mail-storage-service POST /internal/mail/usage` — batch до 200 владельцев
  для административных таблиц; при недоступности хранилища возвращается `usedBytes=null`,
  а не фиктивный ноль.

`alert-relay` принимает `POST /alerts/webhook` и отдаёт `GET /healthz` только в сети
`monitoring`; порт не публикуется. Он вызывает внешний SMTP/Telegram/MAX напрямую и не
участвует в контракте RabbitMQ.
