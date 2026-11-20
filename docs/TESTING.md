# Проверка и приёмка

Как проверить Corvex самому: автотесты, сборка, Docker-стенд и ручная приёмка того, что
тестами не проверяется. Что уже проверено и на каком коммите — [`STATUS.md`](STATUS.md).

Правило, которое здесь важнее остальных: **результат называется ровно тем, чем он был.**
Прошли unit-тесты — это «прошли тесты», а не «почта работает». Поднялись контейнеры — это
«поднялись контейнеры», а не «письмо доставлено».

## 1. Что вы хотите проверить

| Маршрут | Время | Что даёт |
|---|---|---|
| Только интерфейс | 5–10 минут | сборка фронтенда, демо-режим, вёрстка |
| Первая полная приёмка | 1–3 часа | тесты, Maven, Docker, вход, админка, почта, чат, календарь |
| Почтовые протоколы | +1–2 часа | IMAP/POP3 в Thunderbird, submission 587 |

Для любого маршрута нужны: Node.js `^22.22.2 || ^24.15.0 || >=26`, Docker с Compose, JDK 17
и Maven 3.9 для Java-части, Python 3.10+ для служебных скриптов. Для ручной проверки —
Chrome/Edge и, если есть, Chrome 109 на Windows 7.

## 2. Правила безопасности приёмки

- Тестовый домен, тестовые ящики и синтетические письма. Рабочую почту не переносить.
- Не присылать и не публиковать `.env`, токены, ключи, пароли и содержимое реальных писем.
- Два фактора у тестовых пользователей выключаются **на время приёмки** автоматических
  скриптов (они используют пароль); сам 2FA проверяется отдельно вручную.
- Не запускать массовые переносы и удаления на стенде с нужными письмами — см.
  [`MAIL.md`](MAIL.md) §9.
- `docker compose down -v` — только на пустом стенде.

## 3. Фронтенд

```powershell
cd frontend
npm ci
npm test
npm run build
```

Критерий: Vitest зелёный, сборка заканчивается успехом, в `frontend\dist\assets` есть
обычные и `*-legacy-*.js` файлы. Живой просмотр — `npm run dev`, затем
`http://localhost:5173` и кнопка «Посмотреть демо без сервера».

Ручной чек-лист интерфейса:

- [ ] вход открывается без белого экрана и ошибок в Console;
- [ ] RU/EN переключает интерфейс, даты и демо-данные;
- [ ] видна жёлтая плашка демо-режима;
- [ ] почта: папки, список, письмо, ответ и новое письмо;
- [ ] чат: комнаты, сообщения, реакции, длинные слова не ломают вёрстку;
- [ ] админка: пользователи, группы и чекбоксы прав, аудит;
- [ ] ширины 360, 768 и 1366 px без горизонтального скролла;
- [ ] масштаб 200 % не ломает раскладку.

## 4. Java и полный гейт

```powershell
mvn -B clean verify
```

Критерий PASS: все 13 сервисных модулей `SUCCESS`, тесты не пропущены молча, JaCoCo-гейты пройдены,
финал `BUILD SUCCESS`. Если сборка красная — **не переходите к Docker**: сохраните имя
первого упавшего модуля, первую ошибку компиляции или первый failing test и полный
`Caused by:` chain.

Изоляция одного модуля:

```powershell
mvn -B -pl :mail-imap-service -am -DskipTests compile
```

Один прогон всего локального гейта (фронтенд, Maven, `compose config`, сборка образов):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -DockerBuild
```

Финал — `ALL LOCAL GATES PASSED`. Скрипт запускается **после** `first-run.ps1`: без `.env`
шаг `docker compose config` закономерно падает, и это ошибка порядка, а не кода.

## 5. Стенд

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\first-run.ps1
docker compose up -d --build
docker compose ps
```

Критерий PASS: нет сервисов в бесконечном `restarting`; PostgreSQL, Redis, RabbitMQ,
Elasticsearch, ClamAV и Java-сервисы `healthy`; `frontend`, `nginx` и `spamd` — `Up` (у них
нет healthcheck). Flyway у сервисов с БД должен сообщить об успешных миграциях.

Если сервис не поднимается: `docker compose logs --tail 300 <сервис>` и первая причина в
цепочке, а не последняя строка.

## 6. Пользователи, группы, права

Два тестовых пользователя, например `ivanov@company.local` и `petrova@company.local`.

- [ ] администратор создаёт пользователя, тот входит своим паролем;
- [ ] пользователь без прав не открывает админку;
- [ ] изменение чекбоксов группы меняет доступ после обновления сессии;
- [ ] заблокированный пользователь не входит;
- [ ] пять неверных паролей дают временную блокировку;
- [ ] смена пароля работает, старый больше не принимается;
- [ ] TOTP подключается и требуется при входе;
- [ ] app-password показывается один раз и принимается в почтовых протоколах;
- [ ] действия администратора видны в аудите;
- [ ] аналитика директора не даёт доступа к чужим письмам.

**Стоп-критерий:** любой пользователь получил чужие письма или комнату, либо выполнил
административное действие без права. Это блокер безопасности, а не «мелкий баг».

## 7. Почта, чат, календарь, файлы

Два профиля браузера или обычное и приватное окно.

**Webmail.** Письмо от администратора видно получателю без перезагрузки; вложение
скачивается; ответ и пересылка работают; черновик сохраняется и переживает F5; папка
создаётся, письмо переносится; квота отображается.

**Групповые действия.** Два-три письма: выбор, архив, корзина, перемещение через диалог с
выпадающим списком папок. EICAR-файл не прикладывается к групповой пересылке; при
недоступном `mail-storage` пересылка не открывается частично.

**Gmail-подобные ссылки диска.** Файл приватный по умолчанию; ссылка открывается в режиме
инкогнито без входа; после отзыва отдаёт `404`; ссылка на папку не создаётся.

**Чат.** Два пользователя: личный и групповой диалог, чужой доступ запрещён, файл
загружается и проверяется антивирусом, переподключение восстанавливает историю, повторная
отправка с тем же `clientMessageId` не создаёт дубль.

**Календарь.** Событие переживает F5 и появляется во втором браузере; изменение из двух
окон даёт `409`; напоминание приходит письмом при закрытом браузере; перезапуск
`mail-storage-service` не теряет напоминание; переход на летнее время не сдвигает время.

**Перезапуск.** `docker compose restart` для `mail-storage-service`, `chat-service`,
`auth-service` — письма, история чата и сессии на месте.

## 8. SMTP

Сначала локальный приём, потом submission — и только после этого внешняя почта.

**Входящее через 25** (при `CORVEX_SMTP_ENABLED=true`):

```bash
swaks --to ivanov@company.local --from sender@example.net \
      --server 127.0.0.1:25 --header "Subject: corvex inbound"
```

**Submission 587** — STARTTLS, AUTH с app-password, письмо приходит получателю и остаётся
копия в Sent. Чужой `MAIL FROM` отклоняется.

**Негативные проверки:** несуществующий получатель — `550`; релей наружу без авторизации
запрещён; при остановленном storage — `451`, а не `250 OK`; повтор события не создаёт
дубль. EICAR блокируется, при остановленном ClamAV вложение блокируется, а письмо
доставляется.

## 9. IMAP и POP3

Сначала PKCS12 из пилотных PEM (Git Bash, один тестовый пароль на оба файла):

```bash
openssl pkcs12 -export -in infra/certs/fullchain.pem -inkey infra/certs/privkey.pem \
  -out infra/certs/imap.p12 -name corvex-imap
openssl pkcs12 -export -in infra/certs/fullchain.pem -inkey infra/certs/privkey.pem \
  -out infra/certs/pop3.p12 -name corvex-pop3
```

```dotenv
CORVEX_IMAP_ENABLED=true
CORVEX_IMAP_KEYSTORE_PASSWORD=<пароль от imap.p12>
CORVEX_POP3_ENABLED=true
CORVEX_POP3_KEYSTORE_PASSWORD=<пароль от pop3.p12>
CORVEX_POP3_PLAIN_ENABLED=false
```

`first-run` создаёт только PEM-сертификат; для 993/995 нужен PKCS12, и сервис
работает от uid `100`, поэтому файлу нужен владелец `100:101`:

```powershell
docker run --rm -v "${PWD}\infra\certs:/certs" alpine sh -c "apk add --no-cache openssl >/dev/null && openssl pkcs12 -export -in /certs/fullchain.pem -inkey /certs/privkey.pem -out /certs/imap.p12 -passout pass:<пароль-keystore> -name corvex-imap && chown 100:101 /certs/imap.p12 && chmod 640 /certs/imap.p12"
docker compose up -d --force-recreate mail-imap-service mail-pop3-service
docker compose logs --tail 200 mail-imap-service
```

Проверять готовность нужно **по логу**, а не пробой порта: `Test-NetConnection` даёт
ложное «порт открыт», потому что Docker принимает соединение сам, а внутри никто не
слушает — клиент получает обрыв (`WinError 10053`) или отказ (`10061`). В логе должно
быть `listener started` для 143/993. Симптомы в логе:

| Строка | Причина |
|---|---|
| `IMAP отключён флагом CORVEX_IMAP_ENABLED=false` | флаг в `.env` не применён (нужен `--force-recreate`) |
| `Файл не найден или недоступен для чтения: /certs/imap.p12` | файла нет, либо он чужой для uid 100 |
| `HEADLESS`/стектрейс на keystore | неверный `CORVEX_IMAP_KEYSTORE_PASSWORD` |

Пароль для входа — основной или app-password, если включена 2FA.

Отказ IMAP читается по тексту ответа, а не по догадке:

| Ответ | Что значит |
|---|---|
| `NO LOGIN failed. Invalid login/password.` | не подошли логин или пароль (`BadCredentialsException`) |
| `NO LOGIN processing failed.` | пароль **принят**, упал следующий шаг: `provisionInbox` → `mailboxExists` → `mail-storage-service`, либо вызов auth-service после проверки |
| `NO LOGIN failed. Plain login / authentication are disabled.` | `LOGIN` отключён на этом порту |

Второй случай раньше выглядел как «неверный пароль», хотя к паролю отношения не имеет:
James ловит `MailboxException` и отвечает общим текстом. Причину пишет сам сервис, и
`scripts/alert_e2e.py` теперь показывает её рядом с `FAIL`:

```text
[FAIL] imap:alert-email — ... Последняя ошибка подключения: error: b'LOGIN processing failed.'
Сервер принял пароль и упал на следующем шаге ... Причина на сервере: mail-storage-service
недоступен или вернул ошибку: HTTP 503
```

Первый шаг (`mailboxExists`) не имеет права падать: James вызывает его внутри обработки
`LOGIN`, а исключение из `Mono.fromCallable` приходит в `block()` уже как
`ReactiveException`, мимо `catch (MailboxException)`. Тогда в логе не остаётся **ни одной
строки**, и клиент видит только `processing failed`. Поэтому при недоступном хранилище
сервис пишет причину сам и отвечает «папки нет», а James падает уже штатно.

То же вручную:

```powershell
docker compose -p <имя-проекта> logs --tail 500 mail-imap-service | Select-String "IMAP LOGIN|Error encountered while login" -Context 0,8
```

James на любой отказ отвечает одинаково: `LOGIN failed. Invalid login/password.`
Поэтому причину ищут отдельным запросом — тем же, что делает `mail-imap-service`:

```powershell
py scripts\login_probe.py --username admin@company.local --project <имя-проекта>
```

Скрипт спрашивает пароль скрыто и печатает решение auth-service: `SUCCESS` с методом
(основной пароль или пароль приложения) либо `FAIL` с чек-листом причин. Заодно он
сообщает форму введённого значения, не раскрывая его: пароль приложения — ровно
**20 символов**, в интерфейсе показан группами по четыре (`xxxx-xxxx-xxxx-xxxx-xxxx`),
дефисы при вводе необязательны. Символов `0`, `O`, `1`, `l`, `I` в нём не бывает.

Пароль не печатается, не попадает в аргументы и в историю PowerShell. Значение
передаётся в контейнер через окружение процесса, а не через `-e VAR=value`, иначе оно
было бы видно в списке процессов хоста.

Одной командой можно закрыть оба звена — и auth-service, и настоящий IMAP:

```powershell
py scripts\login_probe.py --username admin@company.local --project <имя-проекта> --imap --ca-file infra\certs\fullchain.pem
```

Порядок вывода повторяет путь запроса: сначала `SUCCESS`/`FAIL` от auth-service, затем
`IMAP LOGIN: SUCCESS` или `FAIL`. Комбинация «auth-service сказал SUCCESS, IMAP сказал
FAIL» означает, что пароль верный, а сломано звено между `mail-imap-service` и
auth-service: несовпадение `CORVEX_INTERNAL_API_TOKEN` (401), отклонённое тело (400) или
недоступный auth-service. С версии, где `ImapAuthClient` различает коды, причина
непустая и попадает в лог `mail-imap-service`:

```powershell
docker compose -p <имя-проекта> logs --tail 50 mail-imap-service
```

Проверить, что app-password действительно принят, можно и по `last_used_at`:

```powershell
docker compose exec -T postgres psql -U corvex -d auth_db -c "select label, last_used_at from app_passwords order by created_at desc;"
```

Пусто после успешного `LOGIN` — пароль не совпал, либо IMAP-сервис не смог вызвать
auth-service: при отказе auth-service (401/400/5xx) отметка тоже не ставится, но в логе
будет `auth-service ответил <код>` с подсказкой.

Символы `0/O/1/l/I` в алфавит пароля не входят: если при вводе «поправить» похожий
символ вручную, пароль станет неверным.

**IMAP без клиента:**

```bash
openssl s_client -connect 127.0.0.1:993 -crlf
```

```text
a1 LOGIN ivanov@company.local <app-password>
a2 CAPABILITY
a3 LIST "" "*"
a4 SELECT INBOX
a5 UID FETCH 1:* (FLAGS BODYSTRUCTURE)
a6 LOGOUT
```

PASS: `LOGIN OK`, видны системные папки, `SELECT` показывает `UIDNEXT`/`UIDVALIDITY`,
`FETCH` возвращает флаги и структуру письма.

**POP3:**

```bash
openssl s_client -connect 127.0.0.1:995 -crlf -quiet
```

```text
USER ivanov@company.local
PASS <app-password>
STAT
UIDL
RETR 1
QUIT
```

Отдельно проверьте RFC-семантику на ненужном письме: `DELE <n>`, затем оборвите соединение
без `QUIT`. При повторном входе письмо должно остаться — удаление применяется только на
`QUIT`.

**Thunderbird:** аккаунт создаётся вручную.

| Поле | IMAP | POP3 | Исходящая |
|---|---|---|---|
| Сервер | `mail.company.local` | `mail.company.local` | `mail.company.local` |
| Порт | 993 (SSL/TLS) или 143 (STARTTLS) | 995 (SSL/TLS) | 587 (STARTTLS) |
| Логин | полный e-mail | полный e-mail | полный e-mail |
| Пароль | основной или app-password при 2FA | так же | так же |

Для доверия импортируйте **публичный** CA-сертификат в корневое хранилище пользователя и
включите `security.enterprise_roots.enabled=true`. Не отключайте проверку сертификата и не
добавляйте исключение вместо разбора цепочки.

Чек-лист IMAP: видны INBOX, Sent, Drafts, Trash/Junk и пользовательская папка, включая
вложенную; открываются письмо и вложение; отправка через 587 сохраняет копию в Sent;
перемещение видно после переподключения; удаление + Expunge удаляет навсегда; поиск по
теме находит контрольное письмо; после `docker compose restart mail-imap-service
mail-storage-service` письма и флаги на месте.

Чек-лист POP3: забирается только INBOX; при «оставлять копии на сервере» повторное
получение не плодит дубликаты; удаление из клиента удаляет письмо после `QUIT`.

## 10. Бэкап, откат, алерты

- `backup.ps1` создаёт копию с манифестом SHA-256.
- `restore.ps1 -BackupDir <путь> -ProjectName corvex-restore-drill -DataOnly` +
  `restore_verify.ps1 -BackupDir <путь> -ProjectName corvex-restore-drill` — сравнение
  4 баз и 3 файловых томов, не затрагивая рабочие данные. `-BackupDir` обязателен у обоих
  скриптов, имя проекта обязательно должно отличаться от `corvex`.
- Откат: предыдущий коммит или теги образов + `up -d`.
- Алерт: `scripts/alert_e2e.py` создаёт один синтетический алерт и завершает его; письмо
  приходит на внешний ящик.
- 72 часа наблюдения: очереди, DLQ, диск, память, bounce-rate, отсутствие restart loop.

Расписание и команды — [`OPERATIONS.md`](OPERATIONS.md) §3–5.

## 11. Автоматические проверки в репозитории

| Уровень | Инструмент | Что покрывает | Гейт |
|---|---|---|---|
| Unit (Java) | JUnit 5, Mockito, AssertJ | TOTP по векторам RFC 6238, union прав, имена Maildir, разбор ответов SPAMC/INSTREAM, MIME, правила, квоты | покрытие ≥ 70 %, JaCoCo `check` |
| Контрактные | Spring Test, WireMock, сверка с `docs/api/*.yaml` | статусы, права (403 без permission), Problem Details, схемы событий | расхождение OpenAPI и кода — падение |
| Интеграционные | Testcontainers, GreenMail | конвейер письма целиком, доставка во «внешний» GreenMail, IMAP настоящим JavaMail, Flyway на чистой БД | обязательны для merge |
| Unit (frontend) | Vitest + jsdom | компоненты, i18n-ключи, безопасный HTML, конвертация времени | `npm test` |
| Python | unittest | контракты скриптов и drive-API | `python3 -m unittest discover -s scripts/tests` |
| E2E веб | Playwright (планируется) | вход, письмо туда-обратно, чат двух пользователей | nightly |
| Нагрузка | k6, smtp-source | 1000 пользователей, p95 < 500 мс; чат p95 < 300 мс; SMTP 5 писем/с | перед пилотом |
| Безопасность | OWASP ZAP, dependency-check, ручной проход | open relay, XSS в письмах, IDOR, brute force, утечка поиска | релизный гейт |

Критичные негативные тесты выделены отдельно: open relay, изоляция пользователей, контракты
fail-open (spam) и fail-closed (AV), EICAR в почте и чате.

## 12. Типовые падения

| Симптом | Куда смотреть |
|---|---|
| `npm ci` падает | версия Node не соответствует `engines` в `frontend/package.json` |
| Vitest красный после правки текста | `src/i18n/keys.test.ts` нашёл ключ без перевода |
| Maven красный | первая ошибка компиляции; не понижать версии зависимостей |
| Контейнер `unhealthy` | его полный лог, обычно пароль или память |
| `docker compose config` падает | нет `.env` или `first-run.ps1` не выполнялся |
| Нет писем на 25 | `CORVEX_SMTP_ENABLED=false`, порт закрыт или MX не указывает на сервер |
| IMAP не подключается | нет PKCS12, протокол выключен, пароль вместо app-password |

## 13. Что прислать при ошибке

SHA коммита (`git rev-parse HEAD`), версии (`java -version`, `mvn -v`, `node -v`,
`docker --version`), выполненные команды, первую ошибку целиком, `docker compose ps` и
хвост лога соответствующего сервиса. **Не прикладывайте** `.env`, пароли, токены, ключи и
настоящие письма.
