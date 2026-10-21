# External Mail Connector: старые ящики Mail.ru, Яндекса и других провайдеров в Corvex

[Русский](#русский) · [English](#english)

## Русский

External Mail Connector подключает существующий внешний почтовый ящик к ящику сотрудника в Corvex. Он нужен компаниям, у которых рабочий адрес исторически живёт на `@mail.ru`, `@yandex.ru`, в Gmail/Microsoft 365 или на другом почтовом сервере, а переписку нужно постепенно перенести в собственный Corvex.

> Corvex в целом пока проходит ворота G1–G5 из `READINESS.md`. Коннектор нельзя считать принятым для настоящей рабочей почты, пока Maven, Docker и живой SMTP/IMAP smoke не пройдены. Начинайте с тестового внешнего ящика и synthetic-писем.

### Что умеет коннектор

- пресеты Mail.ru, Яндекс, Gmail и Outlook/Microsoft 365;
- custom IMAP/POP3/SMTP для собственного почтового сервера;
- проверку входящего подключения и SMTP-аутентификации без отправки письма;
- сбор новых писем по расписанию;
- одноразовый импорт архива всех IMAP-папок;
- сохранение папок, дат и флагов `\Seen`, `\Answered`, `\Flagged`, `\Draft`, где их отдаёт источник;
- дедупликацию по IMAP `UIDVALIDITY`+UID, POP3 UIDL, `Message-ID` и SHA-256 сырого `.eml`;
- обычную проверку собранной почты: SpamAssassin → ClamAV → Maildir/метаданные → поиск;
- отправку от имени внешнего адреса через authenticated SMTP провайдера;
- копию в Sent Corvex и, для IMAP, попытку APPEND в Sent провайдера;
- статусы, историю запусков, ручную синхронизацию, паузу, сброс курсоров и повтор после ошибок;
- RBAC-право `MAIL_EXTERNAL_ACCOUNTS`, аудит действий и шифрование секрета AES-256-GCM.

Это односторонний сбор в Corvex. Коннектор не синхронизирует обратно каждое открытие/удаление письма в Corvex и не превращает Corvex в двухсторонний IMAP-агрегатор. Если домен принадлежит компании и DNS можно изменить, долгосрочная цель — перенести MX на Corvex после production-ворот; коннектор закрывает переходный период и адреса на чужих доменах провайдеров.

### Архитектура

```text
Внешний IMAP/POP3
        │  mail-storage-service: аккаунты, AES-GCM секрет, курсоры, дедупликация
        ▼
durable spool + mail.inbound.received
        ▼
SpamAssassin → ClamAV → mail-storage delivery → Maildir + Elasticsearch
                                                  │
                                                  ▼ webmail / IMAP / POP3 Corvex

Webmail (От кого = внешний адрес)
        ▼
mail.outbound.external.queued ──► mail-smtp-service
                                      │ конфигурацию и секрет получает just-in-time
                                      │ по internal API, в RabbitMQ секрет не кладётся
                                      ▼
                              authenticated SMTP провайдера
                                      ▼
                              IMAP APPEND в Sent провайдера
```

`mail-storage-service` владеет аккаунтами и входящим сбором, потому что там уже лежат PostgreSQL/Flyway, папки, Maildir, durable spool и финальная доставка. `mail-smtp-service` владеет только исходящей submission-отправкой к провайдеру.

### Подготовка сервера

1. В `.env` должен быть отдельный ключ:

   ```dotenv
   CORVEX_EXTERNAL_MAIL_ENC_KEY=<Base64 из 32 случайных байт>
   ```

   `scripts/first-run.ps1` генерирует его для нового `.env` и добавляет к существующему, если ключа не было. Ключ должен попадать в защищённый backup `.env`: при его потере сохранённые пароли внешних ящиков расшифровать нельзя, подключения придётся создать заново.

2. Политика по умолчанию безопасна:

   ```dotenv
   CORVEX_EXTERNAL_MAIL_ENABLED=true
   CORVEX_EXTERNAL_MAIL_ALLOW_CUSTOM_HOSTS=true
   CORVEX_EXTERNAL_MAIL_ALLOW_PRIVATE_NETWORKS=false
   CORVEX_EXTERNAL_MAIL_ALLOW_INSECURE_PLAIN=false
   CORVEX_EXTERNAL_MAIL_MAX_ACCOUNTS_PER_USER=10
   CORVEX_EXTERNAL_MAIL_BATCH_SIZE=200
   ```

   `ALLOW_PRIVATE_NETWORKS=true` нужен только для сознательно доверенного внутреннего legacy-сервера. `ALLOW_INSECURE_PLAIN=true` не включайте для интернет-провайдеров: пароль и письма пойдут без TLS.

3. Группе сотрудника должно быть выдано право `MAIL_EXTERNAL_ACCOUNTS`. Администратор с `ADMIN_MAIL_POLICY` также может подключить общий legacy-ящик к отдельному сервисному пользователю Corvex. Уже выданный JWT обновится при refresh сессии.

### Подготовка Mail.ru

1. Войдите в ящик Mail.ru.
2. Создайте отдельный пароль внешнего приложения по официальной инструкции Mail.ru. Обычный пароль от веб-почты для IMAP/SMTP не используется.
3. В Corvex выберите пресет **Mail.ru**. Corvex сам подставит:
   - IMAP `imap.mail.ru:993`, SSL/TLS;
   - POP3 `pop.mail.ru:995`, SSL/TLS;
   - SMTP `smtp.mail.ru:465`, SSL/TLS.
4. Логин обычно совпадает с полным адресом ящика.

### Подготовка Яндекса

1. В настройках почты включите доступ почтовых программ по IMAP.
2. В Яндекс ID создайте пароль приложения для «Почты». Для Яндекс 360 администратор организации также должен разрешить почтовые программы/пароли приложений, если политика организации их запрещает.
3. В Corvex выберите **Яндекс**:
   - IMAP `imap.yandex.ru:993`, SSL/TLS;
   - POP3 `pop.yandex.ru:995`, SSL/TLS;
   - SMTP `smtp.yandex.ru:465`, SSL/TLS.
4. Предпочитайте IMAP. Яндекс прямо предупреждает, что POP3 больше не развивается и его работа не гарантируется так же, как IMAP.

### Подключение в интерфейсе

1. Откройте **Почта → Внешние ящики → Подключить ящик**.
2. Выберите провайдера, укажите адрес, логин и пароль приложения.
3. Для обычной работы оставьте:
   - протокол IMAP;
   - «Только новые письма», если архив не нужен;
   - «Только Входящие», если не нужна миграция всех папок;
   - «Оставлять письма на сервере провайдера» включено;
   - проверка каждые 5 минут;
   - отправка от имени внешнего адреса включена, если нужно отвечать со старого адреса.
4. Нажмите **Проверить подключение**. Corvex отдельно покажет результат IMAP/POP3 и SMTP.
5. Сохраните подключение и нажмите **Синхронизировать сейчас**.
6. Проверьте счётчики и историю: `QUEUED` означает, что письмо ушло в spam/AV-конвейер; `DELIVERED` появится после финальной доставки в папку Corvex.

Пароль приложения после сохранения не показывается. Пустое поле при редактировании означает «оставить текущий пароль».

### Миграция архива

Для одноразового переноса существующей почты:

1. Подключите ящик по IMAP.
2. Выберите существующее подключение.
3. Нажмите **Импорт архива**.
4. Дождитесь завершения запусков. Большие ящики импортируются пакетами (`CORVEX_EXTERNAL_MAIL_BATCH_SIZE`, по умолчанию 200 писем на папку за запуск) и следующими запусками планировщика.
5. Сверьте количество папок/писем, несколько вложений, старые даты и флаги прочтения.
6. Только после контрольной проверки решайте, удалять ли письма у провайдера. По умолчанию Corvex их не удаляет. Если опция удаления включена, письмо удаляется у провайдера не при постановке в очередь, а только после финальной доставки в Corvex — при следующей проверке ящика.

«Импорт архива» сбрасывает IMAP-курсоры папок, но не удаляет дедупликационные записи, поэтому повторный запуск не должен создавать вторые копии уже импортированных писем.

### Общий ящик компании

Для адреса вида `sales@company-mail.ru`, которым пользуются несколько сотрудников:

1. Создайте в Corvex отдельного сервисного пользователя, например `shared-sales@company.local`.
2. Администратор с `ADMIN_MAIL_POLICY` открывает форму внешнего ящика.
3. В разделе «Дополнительно» выбирает «Общий legacy-ящик компании» и указывает UUID сервисного пользователя.
4. Собранная почта будет принадлежать этому ящику Corvex. Доступ к нему организуется существующими правилами учётной записи; полноценные shared-folder ACL — отдельная будущая функция и не обещается коннектором.

### Отправка от имени старого адреса

После подключения в редакторе письма появится селектор **«От кого»**:

- адрес Corvex отправляется существующим direct-to-MX/DKIM контуром Corvex;
- внешний адрес отправляется через SMTP провайдера с его аутентификацией, SPF/DKIM-политикой и лимитами;
- локальные получатели Corvex доставляются внутренним конвейером, нелокальные — через провайдера;
- постоянная ошибка провайдера превращается в DSN в ящике Corvex отправителя;
- Corvex не подписывает такое письмо своим DKIM: подписывать должен провайдер внешнего домена.

Отправлять от имени внешнего ящика может только его владелец-пользователь Corvex. Администратор почты может администрировать подключение, но не получает автоматического права impersonation в webmail.

### Ошибки и диагностика

| Симптом | Что проверить |
|---|---|
| `503 External Mail Connector не настроен` | `CORVEX_EXTERNAL_MAIL_ENC_KEY` задан в `.env`, имеет длину 32 байта после Base64 и контейнер `mail-storage-service` пересоздан после изменения. |
| `AUTH` при тесте | Пароль именно приложения, логин, включён ли IMAP у провайдера, не запрещены ли пароли приложений политикой организации. |
| `TLS` | Правильный хост/порт и тип TLS пресета; системное время сервера; не подменяется ли сертификат корпоративным прокси. |
| `NETWORK` | Исходящие TCP 993/995/465/587 с Docker-хоста, DNS из контейнеров, firewall/провайдер не блокирует подключения. |
| Запрещённый сетевой адрес | Custom host разрешился в loopback/private/link-local. Это SSRF-защита; для доверенного внутреннего сервера администратор сознательно включает `ALLOW_PRIVATE_NETWORKS`. |
| Письма долго `QUEUED` | Очереди и логи spam/AV/storage в RabbitMQ, health SpamAssassin/ClamAV, DLQ, квоту владельца. |
| `FAILED` по квоте | Увеличьте квоту Corvex или освободите место; следующий сбор скачает письмо снова. |
| Письмо больше лимита | Оно помечается `SKIPPED_TOO_LARGE`; увеличьте общий лимит письма только осознанно и с учётом spam/AV/диска. |
| POP3 не даёт UIDL | Без стабильного UIDL безопасная дедупликация невозможна; используйте IMAP. |
| Outlook отклоняет пароль | Многие Microsoft-тенанты требуют Modern Auth/OAuth2. Текущий коннектор честно поддерживает app-password там, где тенант его разрешает; provider-specific OAuth2 — отдельное расширение. |

Не публикуйте в issue пароль приложения, `.env`, internal token, настоящие письма или полные адреса клиентов. Достаточно провайдера, протокола, категории ошибки, времени запуска и обезличенного хвоста логов `mail-storage-service`/`mail-smtp-service`.

### Приёмочный smoke коннектора

На изолированном стенде с отдельным тестовым внешним ящиком:

- [ ] неверный пароль даёт `AUTH`, правильный app-password проходит IMAP и SMTP;
- [ ] новое внешнее письмо появляется в Corvex один раз и находится поиском;
- [ ] повторная ручная синхронизация не создаёт дубль;
- [ ] письмо с вложением проходит AV, EICAR не доставляется как обычное вложение;
- [ ] импорт архива переносит вложенную папку, старую дату и `\Seen`;
- [ ] ответ из webmail от внешнего адреса приходит внешнему получателю и виден в Sent Corvex;
- [ ] при включённом сохранении копия появляется в Sent провайдера;
- [ ] пауза останавливает сбор, возобновление продолжает с курсора;
- [ ] удаление подключения не удаляет уже импортированные письма;
- [ ] пользователь без `MAIL_EXTERNAL_ACCOUNTS` получает 403, чужой аккаунт для него выглядит как 404.

---

## English

External Mail Connector attaches an existing provider mailbox to a Corvex mailbox. It collects new mail over IMAP/POP3, can import an IMAP archive with folders/dates/flags, deduplicates by UIDVALIDITY+UID, POP3 UIDL, Message-ID, and raw SHA-256, passes collected mail through the regular spam → antivirus → storage → search pipeline, and can send as the legacy address through the provider's authenticated SMTP server.

### Setup

1. Generate `CORVEX_EXTERNAL_MAIL_ENC_KEY` (32 random bytes, Base64). `scripts/first-run.ps1` does this. Back this key up with `.env`; losing it means external accounts must be connected again.
2. Grant `MAIL_EXTERNAL_ACCOUNTS` to the employee's group. An `ADMIN_MAIL_POLICY` administrator can attach a shared legacy mailbox to a dedicated Corvex service user.
3. At the provider, create an app password (required by Mail.ru and Yandex) and enable IMAP mail-client access. Prefer IMAP over POP3.
4. In **Mail → External mailboxes**, pick Mail.ru, Yandex, Gmail, Outlook/Microsoft 365, or a custom server; test the connection; save; run **Synchronize now**. Use **Import archive** for a one-time all-folder migration.
5. In the composer, **From** lets the mailbox owner send as the external address through provider SMTP. Corvex does not add its own DKIM signature to such mail.

Secrets are AES-256-GCM encrypted, never returned by the API, and are fetched by the SMTP worker just-in-time over the internal API instead of travelling in RabbitMQ events. Custom endpoints are SSRF-checked (loopback/link-local/private networks and plaintext are blocked by default). Provider-specific OAuth2 is not impersonated as supported: Outlook/Microsoft tenants that require Modern Auth need a separate OAuth integration, while app-password tenants can use the preset.

Corvex remains gated by `READINESS.md`: do not use the connector for real company mail until Maven, Docker, and live mail acceptance pass on an isolated host with a disposable external mailbox.
