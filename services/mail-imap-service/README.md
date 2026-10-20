# mail-imap-service

IMAP4rev2/rev1 сервер Corvex (порты 143 STARTTLS / 993 implicit TLS).

## Как устроен

- Протокольный движок — **Apache James IMAP protocol library**, подключается профилем
  сборки `james` и прячется за портом `ImapServerPort` (см. javadoc интерфейса — там
  обоснование, почему собственный парсер RFC не пишется).
- Хранилище у IMAP своё не появляется: движок работает через адаптер к
  `mail-storage-service` (папки, UID, флаги, тела писем, квота).
- Аутентификация: SASL PLAIN/LOGIN только внутри TLS; проверка пароля — вызовом
  internal API `auth-service` (локальные учётки и AD bind), для внешних клиентов
  (Outlook/Thunderbird) поддерживаются app-passwords при включённой 2FA.
- IDLE-push: `mail-storage-service` публикует `mail.delivered` в RabbitMQ,
  этот сервис вызывает `ImapServerPort.notifyNewMail`.

## Обязательные расширения (приёмка по §11 ТЗ)

`IDLE`, `UIDPLUS`, `MOVE`, `QUOTA`, `SPECIAL-USE`, `NAMESPACE`, `CHILDREN`,
`UNSELECT`, `CONDSTORE` (этап 3 плана разработки).

## Локальный дымовой тест

```bash
openssl s_client -connect localhost:993 -crlf
a1 LOGIN user@corp.local <password>
a2 LIST "" "*"
a3 SELECT INBOX
```
