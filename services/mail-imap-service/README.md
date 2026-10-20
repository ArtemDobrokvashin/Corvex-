# mail-imap-service

IMAP4rev2/rev1 сервер Corvex (порты 143 STARTTLS / 993 implicit TLS).

## Статус этапа 3

**Готов Corvex-слой, движок подключается следующей итерацией.** В этой итерации написаны и
покрыты тестами: внутренний протокольный API в `mail-storage-service`, клиенты
`ImapAuthClient`/`ImapStorageClient`, модель путей папок и флагов. Слушатели 143/993 пока
не открываются: реализация James MailboxManager/MessageManager SPI (~70 реактивных методов
mailbox-api 3.8.2) требует компилятора в цикле обратной связи и делается первым шагом на
JDK-хосте. `CORVEX_IMAP_ENABLED=true` в текущей сборке даёт явную ошибку в логе, а не
молча мёртвый порт.

## Как устроен

- Протокольный движок — **Apache James IMAP protocol library** за портом `ImapServerPort`
  (см. javadoc интерфейса — там обоснование, почему собственный парсер RFC не пишется).
- Хранилища у IMAP своего нет: движок работает через адаптер к `mail-storage-service`
  (`/internal/protocol/users/{userId}/...`, только app-сеть + `X-Corvex-Internal-Token`).
- Аутентификация: SASL PLAIN/LOGIN только внутри TLS; проверка пароля — `ImapAuthClient`
  в `auth-service` (локальные учётки и app-passwords при включённой 2FA; AD bind — этап 5).
- IDLE-push: `mail-storage-service` публикует `mail.delivered` в RabbitMQ, этот сервис
  вызовет `ImapServerPort.notifyNewMail` (точка уже есть в адаптере).

## Таблица соответствия: James SPI -> хранилище (план подключения движка)

| James mailbox-api | Эндпоинт mail-storage / слой Corvex |
| --- | --- |
| `MailboxManager.createMailbox` | `POST /folders` (родитель резолвится `ImapFolderPaths`) |
| `MailboxManager.deleteMailbox` | `DELETE /folders/{id}` (системные папки хранилище защищает 409) |
| `MailboxManager.renameMailbox` | `PATCH /folders/{id}` (+ переименование каталога Maildir) |
| `MailboxManager.search/list` | `GET /folders` + `ImapFolderPaths.toImapPath` (SPECIAL-USE из `specialUse`) |
| `MailboxManager.copyMessages/moveMessages` | `POST /folders/{id}/copy` (`move=true` для MOVE) |
| `MessageManager.getMetaData/getMailboxCounters` | `GET /folders/{id}/status` (UIDNEXT/UIDVALIDITY/счётчики) |
| `MessageManager.getMessages` | `GET /folders/{id}/messages` + `GET .../{uid}/raw` (ENVELOPE/BODYSTRUCTURE строит движок из сырого .eml) |
| `MessageManager.setFlags` | `PATCH .../{uid}/flags` через `ImapFlags.add/remove/replaceWith` |
| `MessageManager.expunge/delete` | `POST /folders/{id}/expunge` (список UID = UID EXPUNGE RFC 4315) |
| `MessageManager.appendMessage` | `POST /folders/{id}/messages` (заголовки `X-Corvex-Internal-Date`, `X-Corvex-Flags`; ответ — APPENDUID) |
| `QuotaManager` (QUOTA RFC 2087) | `GET /quota` (`quotaBytes=0` — без лимита) |
| `Authenticator` James IMAP | `ImapAuthClient.authenticate` |
| IDLE `MailboxListener` | RabbitMQ `mail.delivered` -> `ImapServerPort.notifyNewMail` |

Артефакты для pom на шаге подключения: `org.apache.james:apache-james-mailbox-api:3.8.2`,
`org.apache.james:james-server-protocols-imap4:3.8.2`, `org.apache.james:protocols-netty:3.8.2`
(точные имена сверены с pom-ами тега `james-project-3.8.2`; как и в POP3, Guice-bootstrap
James заменяется ручной сборкой вокруг Netty-транспорта).

## Обязательные расширения (приёмка по ТЗ)

`IDLE`, `UIDPLUS`, `MOVE`, `QUOTA`, `SPECIAL-USE`, `NAMESPACE`, `CHILDREN`, `UNSELECT`,
`CONDSTORE`. Из них на стороне хранилища уже обеспечены данные для UIDPLUS (APPENDUID/COPYUID
из ответов API), MOVE, QUOTA и SPECIAL-USE; CONDSTORE потребует MODSEQ в `message_metadata`
(отдельная миграция на шаге движка).

## Локальный дымовой тест (после подключения движка)

```bash
openssl s_client -connect localhost:993 -crlf
a1 LOGIN user@corp.local <password>
a2 LIST "" "*"
a3 SELECT INBOX
```
