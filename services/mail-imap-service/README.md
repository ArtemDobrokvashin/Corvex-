# mail-imap-service

IMAP4rev1/rev2 сервер Corvex на **Apache James 3.8.2** (143 + STARTTLS / 993 implicit TLS).
TCP/RFC-парсер не пишется вручную: Corvex реализует только James SPI
(`MailboxManager`/`MessageManager`) поверх протокольного API `mail-storage-service`.

## Статус этапа 3

**Движок подключён.** В сервисе реализованы:

- `CorvexMailboxManager` — сессии LOGIN/SASL через `auth-service`, папки, LIST/LSUB-данные,
  CREATE/DELETE/RENAME (включая смену родителя), COPY/MOVE с COPYUID через ответы хранилища,
  мульти-поиск, права владельца, события ящика в James EventBus для IDLE внутри процесса.
- `CorvexMessageManager` — SELECT/STATUS (UIDNEXT/UIDVALIDITY/HIGHESTMODSEQ), FETCH через
  сырой `.eml`, STORE `+FLAGS/-FLAGS/FLAGS`, EXPUNGE/UID EXPUNGE, APPEND (APPENDUID), SEARCH
  по флагам/UID/дате/размеру/MODSEQ/From/Subject/Message-ID/BODY/TEXT.
- `CorvexMessageResult` — BODYSTRUCTURE через `MimeDescriptorImpl` James, BODY[]/HEADER/TEXT
  и MIME-части через Jakarta Mail.
- QUOTA: usage/limit из хранилища/auth (`quotaBytes=0` — без лимита).
- CONDSTORE: миграция `V3__imap_modseq.sql` добавила `mail_folders.highest_modseq` и
  `message_metadata.modseq`; MODSEQ растёт на доставке, APPEND/COPY/MOVE, смене флагов и expunge.

Честные ограничения этой итерации:

- **Подписки LSUB** хранятся в памяти процесса (по умолчанию «все папки») и сбрасываются при
  рестарте; персистентность подписок в хранилище — миграция этапа 5.
- **IDLE** видит события, сделанные этим же процессом IMAP. Доставка из SMTP/storage пока не
  публикует событие именно в IMAP-процесс, поэтому внешняя новая почта гарантированно видна по
  NOOP/пере-SELECT и при следующем IDLE-цикле клиента; распределённый EventBus — этап 5.
- **ACL/METADATA/keyword-флаги** не заявляются возможностями: хранилище держит только
  системные флаги и owner-only права.
- Сервис по умолчанию **выключен** (`CORVEX_IMAP_ENABLED=false`) до клиентского G3.
  Полный G1/G2 на ПК относится только к `b37db56`; на текущем `main` с Boot 3.5.16
  его нужно повторить. Thunderbird/Outlook smoke ещё не выполнен. James 3.8.2 — фактический
  baseline; переход на согласованную поддерживаемую James/JDK-платформу выполняется
  отдельным G4-апгрейдом. Подробности: [`../../docs/READINESS.md`](../../docs/READINESS.md)
  и [`../../docs/RELEASE-DEPENDENCIES.md`](../../docs/RELEASE-DEPENDENCIES.md).

## Запуск и TLS

```dotenv
CORVEX_IMAP_ENABLED=true
CORVEX_IMAP_KEYSTORE_PATH=/certs/imap.p12
CORVEX_IMAP_KEYSTORE_PASSWORD=change-me
CORVEX_IMAP_KEYSTORE_TYPE=PKCS12
```

- 143 публикуется как plain-слушатель + STARTTLS, если найден TLS-материал.
- 993 стартует только при найденном TLS-материале. Если файла keystore нет, сервис не падает:
  993 пропускается с предупреждением, а 143 работает без STARTTLS (только локальный тест!).
- PEM-вариант: очистите `CORVEX_IMAP_KEYSTORE_PATH` и задайте `CORVEX_IMAP_CERT_PATH` +
  `CORVEX_IMAP_PRIVATE_KEY_PATH` (эти переменные читает `application.yml`; в compose по умолчанию
  проброшен именно PKCS12-путь).
- Конвертация PEM -> PKCS12: `openssl pkcs12 -export -inkey key.pem -in cert.pem -out imap.p12`.

Для боевого режима после smoke имеет смысл `CORVEX_IMAP_REQUIRE_TLS_FOR_AUTH=true`: James запретит
LOGIN до STARTTLS на plain-порту.

## Таблица соответствия: James SPI -> хранилище

| James mailbox-api | Эндпоинт mail-storage / слой Corvex |
| --- | --- |
| `MailboxManager.createMailbox` | `POST /folders` по сегментам пути (`ImapFolderPaths`, разделитель `/`) |
| `MailboxManager.deleteMailbox` | `DELETE /folders/{id}` (системные папки хранилище защищает 409) |
| `MailboxManager.renameMailbox` | `PATCH /folders/{id}` с `name + parentId/moveToRoot` |
| `MailboxManager.search/list/hasChildren` | `GET /folders` + дерево `parentId` |
| `MailboxManager.copyMessages/moveMessages` | `POST /folders/{id}/copy` (`move=true` для MOVE) |
| `MessageManager.getMetaData/getMailboxCounters` | `GET /folders/{id}/status` (UIDNEXT/UIDVALIDITY/HIGHESTMODSEQ/счётчики) |
| `MessageManager.getMessages` | `GET /folders/{id}/messages` + `GET .../{uid}/raw` |
| `MessageManager.setFlags` | `PATCH .../{uid}/flags` (полный снапшот после ADD/REMOVE/REPLACE) |
| `MessageManager.expunge/delete` | `POST /folders/{id}/expunge` (список UID = UID EXPUNGE RFC 4315) |
| `MessageManager.appendMessage` | `POST /folders/{id}/messages` (`X-Corvex-Internal-Date`, `X-Corvex-Flags`; ответ — APPENDUID + MODSEQ) |
| `QuotaManager/QuotaRootResolver` | `GET /quota`, root = `#private&user@domain` |
| `SessionProvider.authenticate` | `ImapAuthClient.authenticate` (пароль или app-password при 2FA) |
| `SubscriptionManager` | in-memory набор этого процесса (см. ограничение выше) |

## Дымовой тест после сборки

```bash
openssl s_client -connect localhost:993 -crlf
a1 LOGIN user@corp.local <password>
a2 CAPABILITY
a3 LIST "" "*"
a4 SELECT INBOX
a5 UID FETCH 1:* (FLAGS BODYSTRUCTURE)
a6 LOGOUT
```

Или Thunderbird: Incoming IMAP, сервер — ваш `MAIL_HOSTNAME`, 993 SSL/TLS или 143 STARTTLS,
логин — полный e-mail пользователя, пароль — обычный пароль Corvex (при включённой 2FA — app-password).

## Проверка модуля

Проверка начинается с изолированной сборки модуля, а не с публичного включения порта:

```bash
mvn -B -pl :mail-imap-service -am -DskipTests compile
mvn -B -pl :mail-imap-service -am test
```

Для issue по красной сборке нужны SHA коммита, версии JDK/Maven и
**первая** ошибка компилятора или теста. После зелёной сборки клиентская
матрица включает CAPABILITY, LOGIN с app-password, LIST вложенных папок,
SELECT/FETCH письма с вложением, отправка с копией в Sent, MOVE/EXPUNGE,
SEARCH, QUOTA, CONDSTORE/MODSEQ и поведение IDLE после внешней доставки.

Следующие задачи после зелёного baseline: персистентные LSUB,
распределённые события IDLE из SMTP/storage, keyword-флаги/ACL, нагрузочный
профиль больших папок и отдельный апгрейд James 3.8.2 → 3.9.x с повторной
сверкой SPI. Правила вклада: [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md).
