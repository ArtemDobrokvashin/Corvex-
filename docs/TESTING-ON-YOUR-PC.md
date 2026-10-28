# Corvex: поэтапная инструкция по тестированию на вашем ПК

> **Дата актуализации:** 28 октября 2026 года.
> **Проверяемый исходный код:** актуальный `main`; точный SHA запишите до старта.
> Текущий backend использует Boot 3.5.16, dependency-пины — в `RELEASE-DEPENDENCIES.md`.
> **Исторический PC-pass G1/G2:** только `b37db56` (Boot 3.2.12 / Cloud 2023.0.6 /
> James 3.8.2). На текущем `main` G1/G2 нужно повторить. G0 повторно проверен на
> текущем `main`; G3 частично идёт: External Mail ongoing и базовый realtime-чат,
> но собственные протоколы, полный чат, архив, restore и Chrome 109 ещё не приняты.
> Это **не production-ready**.

Эта инструкция позволяет воспроизвести проверки на вашем точном SHA и продолжить приёмку.
Идите сверху вниз и **не переходите к следующему уровню, если предыдущий красный**: так
первая ошибка не утонет в сотнях вторичных последствий. Выполняйте по одной логической
команде, дожидайтесь результата и не публикуйте секреты.

## Фактический статус проверок

| Проверка | Результат | Комментарий |
|---|---|---|
| Frontend `npm ci` | ✅ PASS | Повторено на текущем `main` 28.10.2026, Node 22.22.3 / npm 10.9.8 |
| Frontend unit-тесты | ✅ PASS | **9 test-файлов, 33/33 теста** |
| TypeScript + production build | ✅ PASS | Modern и `*-legacy-*.js` bundles созданы |
| Production `npm audit` | ✅ PASS | **0 уязвимостей** на текущем lockfile |
| Корневой `mvn -B clean verify` | ✅ PASS только на `b37db56` | 12 сервисов + parent, Boot 3.2.12; на текущем `main` с Boot 3.5.16 повторить |
| Docker build / Compose | ✅ PASS только на `b37db56` | 13 образов, Compose config, базовый старт; на текущем `main` повторить |
| Spring/Flyway/БД/очереди | ✅ Базовый PASS на `b37db56` | 20/20 основных контейнеров; healthchecked — `healthy`, `nginx`/`frontend`/`spamd` — `Up`; текущий `main` повторить |
| Web runtime и базовый чат | 🟡 Частично | Вход, админка, webmail, история/SockJS/сообщения/emoji проверены; полный двухпользовательский сценарий остаётся |
| External Mail ongoing | 🟡 Частично | Mail.ru/Яндекс на тестовых ящиках, polling и dedupe; архив и остальные провайдеры не приняты |
| Собственные SMTP/IMAP/POP3 | ⏸ G3 НЕ ЗАВЕРШЁН | Maven/Docker пройдены только на `b37db56`, на `main` повторить; end-to-end и Thunderbird/Outlook ещё не проверены |
| Windows 7 + Chrome 109 | ⏸ НЕ ПРОВЕРЕНО | Legacy bundle построен, но нужен реальный браузерный smoke |
| Production / 1000 пользователей | ⏸ НЕ ПРОВЕРЕНО | Нагрузка, 72 часа, backup/restore, откат и пилот остаются в G3–G5 |

Это результаты локальных/PC-проверок, а не выдуманные GitHub Actions checks. Полная
матрица: [`READINESS.md`](READINESS.md); версии: [`RELEASE-DEPENDENCIES.md`](RELEASE-DEPENDENCIES.md).

---

## Правила безопасности этого тестирования

1. Тестируйте на отдельном ПК или VM. **Не направляйте боевые MX** на Corvex.
2. Не пробрасывайте порты тестового ПК в Интернет и не переносите настоящие ящики.
3. До отдельных шагов ниже оставляйте:
   - `CORVEX_SMTP_ENABLED=false`
   - `CORVEX_IMAP_ENABLED=false`
   - `CORVEX_POP3_ENABLED=false`
4. Не присылайте в issue/чат `.env`, пароли, `CORVEX_INTERNAL_API_TOKEN`, TOTP-секреты,
   приватные ключи и настоящие письма.
5. `docker compose down -v` удаляет **все данные томов**. Команда допустима только на
   пустом стенде, если вы осознанно начинаете с нуля.

---

## Выберите глубину проверки

### Маршрут A — только интерфейс, 5–10 минут

Шаги 1–2. Подтверждает только frontend и демо-режим без сервера.

### Маршрут B — рекомендуемая первая полная приёмка, 1–3 часа

Шаги 1–7 и 10. Подтверждает Maven, Docker-образы, старт стека, вход, админку,
webmail, чат и сохранение данных после рестарта.

### Маршрут C — почтовые протоколы

После зелёного маршрута B выполните шаги 8–9. Это первая проверка SMTP submission,
IMAP и POP3 реальными клиентами.

---

## 0. Что установить на ПК

Для маршрута A нужен только Node.js актуального поддерживаемого patch-ряда: 22.22.2+, 24.15+ или 26+ (см. `engines` во frontend). Для B/C нужны все инструменты:

- Windows 10/11 или Windows Server с Linux-VM по `docs/INSTALL.md`;
- Git;
- **JDK 17 LTS** (проект настроен на Java 17; не начинайте первую приёмку с другой major-версии);
- Maven 3.9+;
- **Node.js 22.22.2+ LTS** (также допустимы 24.15+ и 26+ по `frontend/package.json`) и npm 10+;
- Docker Desktop (WSL2) или Docker Engine + Docker Compose v2;
- желательно Thunderbird для шага 9;
- OpenSSL обычно приходит с Git for Windows и запускается из Git Bash.

Рекомендуемый минимум для полного стека на одном ПК: **16 ГБ RAM**, 6 CPU и 30+ ГБ
свободного SSD. Docker Desktop отдайте минимум 12 ГБ RAM: одновременно работают
PostgreSQL, Elasticsearch, ClamAV и 12 JVM-сервисов. На 8 ГБ полный стек может падать
по памяти, и это будет не доказательство ошибки кода.

Проверка версий в PowerShell:

```powershell
git --version
java -version
mvn -version
node --version
npm --version
docker --version
docker compose version
docker info
```

**Критерий PASS:** все команды находятся; `java` показывает 17.x; `docker info`
завершается без `Cannot connect to the Docker daemon`.

---

## 1. Получить именно актуальный код

Используйте актуальный **`main`** либо точный SHA pull request, который вы согласовали
для проверки. Не используйте старые merged feature-ветки: отчёт должен относиться к коду,
который увидят другие тестировщики.

### Новый клон

```powershell
cd C:\
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git Corvex-test
cd C:\Corvex-test
git log --oneline -4
git status --short --branch
```

### Уже есть клон

```powershell
cd C:\путь\к\Corvex-
git fetch origin
git switch main
git pull --ff-only
git log --oneline -4
git status --short --branch
```

Если проверяете PR, после `git fetch origin` перейдите на его ветку/SHA по инструкции PR,
а не смешивайте его с локальными изменениями. Запишите точный SHA до начала:

```powershell
git rev-parse HEAD
```

Этот SHA нужен в любом отчёте об ошибке. Результаты из таблицы выше относятся к своему
baseline и автоматически не доказывают другой коммит.

---

## 2. Frontend: тесты, production-сборка и живой запуск

Из корня репозитория:

```powershell
cd frontend
npm ci
npm test
npm run build
```

**Ожидаемый результат:**

- Vitest: `Test Files 9 passed`, `Tests 33 passed`;
- сборка заканчивается `✓ built`;
- в `frontend\dist\assets` есть обычные и `*-legacy-*.js` файлы.

Запуск для ручного просмотра:

```powershell
npm run dev
```

Откройте <http://localhost:5173> и нажмите **«Посмотреть демо без сервера»**.

Проверьте вручную:

- [ ] Экран входа открывается без белого экрана и критичных ошибок в DevTools Console.
- [ ] Переключение RU/EN меняет интерфейс, даты и demo-данные.
- [ ] В демо видна жёлтая плашка демо-режима.
- [ ] Почта: папки, список, письмо, окно ответа/нового письма.
- [ ] Чат: список комнат, сообщения, emoji-picker, длинные слова и эмодзи не ломают вёрстку.
- [ ] Админка: пользователи, группы и чекбоксы прав, аудит.
- [ ] DevTools → Device Toolbar: ширины 360, 768 и 1366 px без горизонтального скролла страницы.

Остановка dev-сервера: `Ctrl+C`.

> Если вы смотрите отдельную демо-сборку frontend без поднятого Java-бэкенда,
> используйте именно кнопку демо: настоящий логин там не заработает, и это ожидаемо.

Вернитесь в корень:

```powershell
cd ..
```

---

## 3. Первая настоящая проверка Java: Maven

G1 пройден на ПК только для `b37db56` с Boot 3.2.12. Текущий `main` уже использует Boot 3.5.16, поэтому полный прогон на вашем актуальном SHA обязателен.

```powershell
mvn -B clean verify
```

**Критерий PASS:**

- все 12 модулей в Reactor Summary имеют `SUCCESS`;
- тесты не пропущены молча;
- JaCoCo-gates проходят;
- финал: `BUILD SUCCESS`.

Если сборка красная, **не запускайте Docker дальше**. Сохраните:

1. имя первого упавшего модуля;
2. первую ошибку `COMPILATION ERROR` или первый failing test;
3. полный `Caused by:` chain для Spring-ошибок;
4. для теста — файл из `services\<сервис>\target\surefire-reports\*.txt`.

Быстрая изоляция одного модуля без тестов:

```powershell
mvn -B -pl :mail-imap-service -am -DskipTests compile
```

Подставьте имя упавшего сервиса. Не лечите первую ошибку массовым понижением версий
Spring/James и не отключайте JaCoCo ради «зелёной галочки» без отдельного решения.

---

## 4. Инициализировать тестовый стенд и собрать Docker-образы

Docker должен быть запущен **до** `first-run.ps1`: скрипт использует временный
Alpine/OpenSSL-контейнер как генератор ключей и пилотного сертификата.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\first-run.ps1
```

Скрипт безопасно не перезаписывает существующие `.env`, ключи и сертификаты.
Должны появиться:

- `.env`;
- `infra\secrets\jwt_private.pem`;
- `infra\secrets\dkim_private.pem`;
- `infra\certs\fullchain.pem`;
- `infra\certs\privkey.pem`.

Откройте `.env`:

```powershell
notepad .env
```

Для первого локального теста оставьте значения по умолчанию:

```dotenv
CORVEX_DOMAIN=mail.company.local
CORVEX_MAIL_DOMAIN=company.local
CORVEX_SMTP_ENABLED=false
CORVEX_IMAP_ENABLED=false
CORVEX_POP3_ENABLED=false
```

Рекомендуется до первого `docker compose up` явно задать длинный тестовый пароль
первого администратора, чтобы потом не искать сгенерированный в логах:

```dotenv
CORVEX_BOOTSTRAP_ADMIN_USERNAME=admin
CORVEX_BOOTSTRAP_ADMIN_PASSWORD=<придумайте длинный тестовый пароль>
CORVEX_BOOTSTRAP_ADMIN_EMAIL=admin@company.local
```

Проверьте, что в `.env` не осталось `change-me-strong-password`.

### Локальное имя `mail.company.local`

Nginx и пилотный сертификат рассчитаны на `mail.company.local`. На тестовом ПК
добавьте это имя в `hosts`. Откройте PowerShell **от имени администратора**:

```powershell
$hosts = "$env:SystemRoot\System32\drivers\etc\hosts"
if (-not (Select-String -Path $hosts -SimpleMatch "mail.company.local" -Quiet)) {
  Add-Content $hosts "`n127.0.0.1 mail.company.local"
}
ping mail.company.local
```

### Официальный полный гейт одной командой

После создания `.env`:

```powershell
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -DockerBuild
```

Скрипт повторно выполнит frontend-тесты/сборку, `mvn -B clean verify`,
`docker compose config -q` и сборку всех Docker-образов. Финал должен быть:

```text
ALL LOCAL GATES PASSED
```

> Если запустить `verify.ps1` до `first-run.ps1`, шаг `docker compose config`
> закономерно упадёт из-за отсутствующих обязательных секретов. Это ошибка порядка
> запуска, а не обязательно ошибка кода.

---

## 5. Поднять полный стек и дождаться готовности

```powershell
docker compose up -d
docker compose ps
```

Первый старт может занять несколько минут: Elasticsearch инициализируется,
ClamAV загружает сигнатуры, Flyway создаёт схемы. Повторяйте `docker compose ps`,
пока сервисы с healthcheck не станут `healthy`, а остальные не будут стабильно `Up`.

**Критерий PASS:**

- нет сервисов в бесконечном `restarting`;
- нет `Exited` у основных сервисов;
- PostgreSQL, Redis, RabbitMQ, Elasticsearch, ClamAV и Java-сервисы `healthy`;
- `frontend`, `nginx` и `spamd` запущены (`Up`): для них healthcheck в Compose не задан,
  поэтому статус `healthy` у этих трёх контейнеров ожидать не нужно.

Если сервис unhealthy/restarting, смотрите именно его полный лог:

```powershell
docker compose logs --tail 300 <имя-сервиса>
```

Проверьте Flyway у сервисов с БД:

```powershell
docker compose logs auth-service | Select-String Flyway
docker compose logs admin-service | Select-String Flyway
docker compose logs chat-service | Select-String Flyway
docker compose logs mail-storage-service | Select-String Flyway
```

Проверка edge/gateway без логина:

```powershell
curl.exe -k -i https://mail.company.local/api/v1/auth/me
```

**Ожидаемо:** `401` (gateway жив и требует аутентификацию).
`502` — gateway/frontend/nginx не договорились; смотрите логи этих трёх сервисов.
Ошибка сертификата при обычном браузере на этом стенде ожидаема: сертификат
самоподписанный. Подтверждайте исключение только на вашем закрытом тестовом ПК.

Откройте <https://mail.company.local>:

- логин: `admin` или значение `CORVEX_BOOTSTRAP_ADMIN_USERNAME`;
- пароль: значение из `.env`, а если оно пустое — один раз напечатанный пароль:

```powershell
docker compose logs auth-service | Select-String BOOTSTRAP
```

Сразу смените пароль, если интерфейс требует.

---

## 6. Smoke: пользователи, группы, права и безопасность входа

Создайте в админке как минимум двух обычных тестовых пользователей, например:

- `ivanov@company.local`;
- `petrova@company.local`.

Проверьте по чек-листу:

- [ ] Администратор создаёт пользователя, пользователь входит своим паролем.
- [ ] Пользователь без админ-прав не видит/не открывает админку как администратор.
- [ ] Смена чекбоксов группы меняет доступ после повторного входа/обновления сессии.
- [ ] Заблокированный пользователь не может войти.
- [ ] Пять неверных паролей приводят к временной блокировке входа (lockout).
- [ ] Смена пароля работает, старый пароль больше не принимается.
- [ ] TOTP можно подключить по QR/секрету; вход требует второй фактор.
- [ ] App-password создаётся и показывается один раз; сохраните его отдельно для шага 9.
- [ ] Действия администратора появляются в аудите.
- [ ] Директорская аналитика не превращается в доступ к чужим письмам.

**Стоп-критерий:** любой пользователь получил чужие письма/комнату или админ-действие
без права. Это security-блокер, а не «мелкий баг интерфейса».

---

## 7. Smoke: webmail и чат между двумя пользователями

Используйте два разных профиля браузера или обычный режим + приватное окно.

### Webmail

- [ ] Иванов отправляет Petrova письмо с уникальной темой, например `Corvex smoke 2026-10-20 001`.
- [ ] В письмо вложен небольшой текстовый или PDF-файл.
- [ ] Petrova видит письмо в INBOX, открывает тело и скачивает вложение.
- [ ] Поиск по уникальной теме находит письмо.
- [ ] Прочтение/флажок/перемещение между папками сохраняются после обновления страницы.
- [ ] У Ivanov копия есть в Sent.
- [ ] Ответ Petrova приходит Ivanov.
- [ ] Письмо несуществующему локальному адресату не «успешно теряется»: UI/API показывает понятную ошибку или DSN по предусмотренному сценарию.

### Чат

- [ ] Создаётся direct-диалог и групповая комната.
- [ ] Сообщения видны второй стороне без ручного refresh.
- [ ] Работают edit/delete, реакции, pin, read-состояния и typing/presence, если они видны в UI.
- [ ] Файл загружается, второй участник скачивает его.
- [ ] Пользователь вне комнаты не открывает историю и не скачивает файл по прямой ссылке/API.
- [ ] Повторная отправка/переподключение не создаёт дубль сообщения.
- [ ] Официальный EICAR-тест-файл отклоняется антивирусом.

### Перезапуск без потери данных

Запомните уникальную тему письма и текст сообщения чата, затем:

```powershell
docker compose down
docker compose up -d
docker compose ps
```

Не добавляйте `-v`. После `healthy` снова войдите и проверьте то же письмо, вложение,
папки/флаги, комнату и файл чата.

---

## 8. SMTP: сначала локальный приём, потом submission

Выполняйте только после зелёных шагов 5–7 и только на стенде.

В `.env` измените:

```dotenv
CORVEX_SMTP_ENABLED=true
```

Пересоздайте один сервис:

```powershell
docker compose up -d --force-recreate mail-smtp-service
docker compose logs --tail 200 mail-smtp-service
Test-NetConnection 127.0.0.1 -Port 25
Test-NetConnection 127.0.0.1 -Port 587
```

### Входящее письмо через порт 25

Создайте `test-inbound.eml` в корне репозитория:

```powershell
@'
From: External Test <external@example.org>
To: ivanov@company.local
Subject: Corvex SMTP inbound smoke 001
Content-Type: text/plain; charset=utf-8

Hello from the local SMTP smoke test.
'@ | Set-Content .\test-inbound.eml -Encoding ascii
```

Отправьте встроенным Windows `curl.exe`:

```powershell
curl.exe --verbose --url "smtp://127.0.0.1:25" `
  --mail-from "external@example.org" `
  --mail-rcpt "ivanov@company.local" `
  --upload-file .\test-inbound.eml
```

**PASS:** SMTP отвечает `250`, а письмо появляется у Ivanov после прохождения
конвейера spam → AV → storage. Если SMTP вернул `250`, но письма нет, смотрите по цепочке:

```powershell
docker compose logs --tail 200 mail-smtp-service mail-spam-service mail-antivirus-service mail-storage-service
```

Негативные проверки:

- [ ] Несуществующий локальный получатель получает `550`, а не молчаливое исчезновение.
- [ ] Без AUTH через порт 25 нельзя отправить письмо на внешний домен (**open relay запрещён**).
- [ ] При остановленных storage/RabbitMQ SMTP не врёт `250 OK`: ожидается временная ошибка `451` (эту проверку делайте только на пустом стенде).

### Submission 587

Проверяйте Thunderbird-аккаунтом из шага 9: исходящий сервер
`mail.company.local`, порт `587`, STARTTLS, обычная аутентификация, логин — полный
e-mail, пароль — основной или app-password при 2FA. Чужие `From` должны отклоняться.

Внешнюю доставляемость на Gmail/Yandex/корпоративный MX **не** проверяйте с
`company.local`: для неё нужны публичный домен, статический IP, PTR, SPF, DKIM и DMARC
по `docs/INSTALL.md`.

---

## 9. IMAP/POP3: сертификаты, OpenSSL и Thunderbird

### 9.1. Сделать PKCS12 из пилотных PEM

Откройте **Git Bash** в корне репозитория. OpenSSL дважды спросит пароль экспорта;
задайте один тестовый пароль для обоих файлов и запомните его:

```bash
openssl pkcs12 -export \
  -in infra/certs/fullchain.pem \
  -inkey infra/certs/privkey.pem \
  -out infra/certs/imap.p12 \
  -name corvex-imap

openssl pkcs12 -export \
  -in infra/certs/fullchain.pem \
  -inkey infra/certs/privkey.pem \
  -out infra/certs/pop3.p12 \
  -name corvex-pop3
```

В `.env` укажите этот пароль и включите протоколы:

```dotenv
CORVEX_IMAP_ENABLED=true
CORVEX_IMAP_KEYSTORE_PASSWORD=<пароль от imap.p12>
CORVEX_POP3_ENABLED=true
CORVEX_POP3_KEYSTORE_PASSWORD=<пароль от pop3.p12>
CORVEX_POP3_PLAIN_ENABLED=false
```

Пересоздайте протокольные сервисы:

```powershell
docker compose up -d --force-recreate mail-imap-service mail-pop3-service
docker compose logs --tail 200 mail-imap-service
docker compose logs --tail 200 mail-pop3-service
Test-NetConnection 127.0.0.1 -Port 993
Test-NetConnection 127.0.0.1 -Port 995
```

Если порт закрыт, сначала смотрите лог: самая частая причина — отсутствующий `.p12`
или неверный пароль keystore.

### 9.2. Быстрый IMAP-smoke без клиента

В Git Bash:

```bash
openssl s_client -connect 127.0.0.1:993 -crlf
```

Вводите построчно:

```text
a1 LOGIN ivanov@company.local <пароль или app-password>
a2 CAPABILITY
a3 LIST "" "*"
a4 SELECT INBOX
a5 UID FETCH 1:* (FLAGS BODYSTRUCTURE)
a6 LOGOUT
```

**PASS:** LOGIN `OK`, видны INBOX и системные папки, SELECT показывает UIDNEXT/
UIDVALIDITY, FETCH возвращает флаги и структуру тестового письма.

### 9.3. Быстрый POP3-smoke

```bash
openssl s_client -connect 127.0.0.1:995 -crlf -quiet
```

```text
USER ivanov@company.local
PASS <пароль или app-password>
STAT
UIDL
RETR 1
QUIT
```

Отдельно на ненужном тестовом письме проверьте RFC-семантику: `DELE <n>`, затем
**оборвите соединение без QUIT** (`Ctrl+C`). При повторном входе письмо должно остаться:
удаление применяется только на QUIT.

### 9.4. Thunderbird

Создайте аккаунт вручную:

| Поле | IMAP | POP3 | Исходящая почта |
|---|---|---|---|
| Сервер | `mail.company.local` | `mail.company.local` | `mail.company.local` |
| Порт | 993 | 995 | 587 |
| Защита | SSL/TLS | SSL/TLS | STARTTLS |
| Аутентификация | Обычный пароль | Обычный пароль | Обычный пароль |
| Логин | полный e-mail | полный e-mail | полный e-mail |
| Пароль | основной или app-password при 2FA | основной или app-password | основной или app-password |

Самоподписанный сертификат вызовет предупреждение. На закрытом стенде сверьте,
что подключаетесь именно к своему `mail.company.local`, и только после этого
подтверждайте исключение. Для пилота с пользователями нужен нормальный сертификат
корпоративного CA или Let's Encrypt с SAN на почтовое имя.

Чек-лист IMAP:

- [ ] Видны INBOX, Sent, Drafts, Trash/Junk и вложенная пользовательская папка.
- [ ] Открываются письмо и вложение из шага 7/8.
- [ ] Отправка через 587 сохраняет копию в Sent.
- [ ] Перемещение письма между папками видно после переподключения.
- [ ] Удаление + Expunge/«Очистить корзину» удаляет навсегда.
- [ ] Поиск Thunderbird по теме находит контрольное письмо.
- [ ] После `docker compose restart mail-imap-service mail-storage-service` те же письма и флаги на месте.

Чек-лист POP3:

- [ ] Забирается только INBOX.
- [ ] При «оставлять копии на сервере» повторное получение не плодит дубликаты в клиенте.
- [ ] Удаление из клиента при соответствующей настройке удаляет письмо после QUIT.

---

## 10. Что ещё проверить перед словом «пилот»

Эти пункты не нужны, чтобы впервые увидеть интерфейс, но обязательны до пилота:

- [ ] Мониторинг: `docker compose --profile monitoring up -d`, Grafana/Loki доступны только из админ-сети.
- [ ] Алерт: остановка некритичного сервиса приводит к email-уведомлению на внешний ящик.
- [ ] Backup на отдельный носитель: `scripts\backup.ps1`.
- [ ] **Учебный restore** на чистой VM/папке: ящики, чаты и файлы реально восстановились.
- [ ] Рестарт всего ПК/VM: стек поднимается, данные не потеряны.
- [ ] Windows 7 + Chrome 109 на реальной VM: загружается legacy-бандл, работают вход, почта, чат и админка.
- [ ] Современный Chrome/Edge/Firefox и реальный смартфон/планшет.
- [ ] Негативная безопасность: чужое письмо по прямому ID, чужая комната/файл, open relay, brute-force lockout.
- [ ] Публичный домен: PTR/SPF/DKIM/DMARC, mail-tester ≥ 9/10, внешний контролируемый получатель.
- [ ] Минимум 72 часа стенда без restart-loop, роста DLQ и необъяснённых потерь писем.

Подробные продуктовые ворота остаются в [`READINESS.md`](READINESS.md).

---

## Как называть результат, чтобы не обманывать себя

| Что прошло | Как честно называть |
|---|---|
| Только шаг 2 | «Frontend собирается и демо работает» |
| Шаги 2–4 | «Код и Docker-образы собираются на моём ПК» |
| Шаги 2–5 | «Стек стартует и сервисы становятся healthy» |
| Шаги 2–7 | «Webmail/чат прошли локальный smoke» |
| Шаги 2–9 | «SMTP/IMAP/POP3 прошли локальный клиентский smoke» |
| Всё выше + шаг 10 и 72 часа наблюдения | «Кандидат в ограниченный пилот» |
| Production MX и рабочие ящики | Только после пилота, backup/restore, deliverability и security-приёмки |

---

## Типовые падения и куда смотреть

| Симптом | Вероятная причина | Действие |
|---|---|---|
| Maven: первая `COMPILATION ERROR` | Реальное расхождение API/типов | Остановитесь; пришлите первую ошибку и модуль, не сотню следующих |
| Maven: тесты зелёные, `verify` красный на JaCoCo | Покрытие ниже порога | Это отдельный gate; не отключайте молча перед релизом |
| `docker compose config`: переменная обязательна | Не выполнен `first-run.ps1` или placeholder в `.env` | Сгенерировать `.env`, проверить обязательные пароли/токен |
| Docker build долго качает зависимости | Первый холодный кэш Maven/npm | Подождать; проверить сеть/прокси/место на диске |
| Сервис `restarting` | Spring bean/config, Flyway/JPA, недоступная инфраструктура, OOM | `docker compose logs --tail 300 <сервис>`, читать последний `Caused by` |
| Flyway/JPA validate | Схема и entity разошлись | Нужна **новая** миграция; применённую не редактировать |
| Веб `502` | Не жив gateway/frontend или nginx upstream | Логи `nginx`, `web-gateway`, `frontend`; проверить `docker compose ps` |
| Веб не открывается по имени | Нет hosts/DNS или `server_name` не совпал с доменом | `ping mail.company.local`, `infra\nginx\conf.d\corvex.conf` |
| Vite на 5173: API 500/ECONNREFUSED | Dev-frontend проксирует `/api` в localhost:8080, а полного стека там нет | Для демо — кнопка без сервера; для полного стека открывайте `https://mail.company.local`, не Vite |
| SMTP `451` | Storage/Rabbit/spam/AV недоступны или права/квота | Логи SMTP/storage/spam/AV и очереди RabbitMQ |
| IMAP/POP3 порт закрыт | Флаг `*_ENABLED=false`, нет `.p12`, неверный пароль | `.env`, наличие `infra\certs\*.p12`, логи протокольного сервиса |
| Thunderbird ругается на сертификат | Самоподписанный пилотный cert/нет SAN | Только стенд: подтвердить свой cert; пилоту нужен нормальный CA |
| После рестарта пропали данные | Случайно использован `down -v` или другой проект/папка | Не продолжать запись; проверить `docker volume ls` и восстанавливать из backup |

---

## Что прислать мне при первой ошибке

Создайте папку диагностики вне репозитория:

```powershell
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = "$env:USERPROFILE\Documents\corvex-diagnostics-$stamp"
New-Item -ItemType Directory -Force $out | Out-Null

git rev-parse HEAD > "$out\commit.txt"
git status --short --branch > "$out\git-status.txt"
java -version > "$out\versions.txt" 2>&1
mvn -version >> "$out\versions.txt" 2>&1
node --version >> "$out\versions.txt" 2>&1
npm --version >> "$out\versions.txt" 2>&1
docker --version >> "$out\versions.txt" 2>&1
docker compose version >> "$out\versions.txt" 2>&1
docker compose ps > "$out\compose-ps.txt" 2>&1
```

Добавьте:

- точную команду, которая упала;
- **первую** ошибку Maven или полный Spring `Caused by` chain;
- лог только упавшего сервиса:

```powershell
docker compose logs --no-color --tail 300 <имя-сервиса> > "$out\<имя-сервиса>.log" 2>&1
```

Перед отправкой поиском по папке удалите/замаскируйте строки с `PASSWORD`, `TOKEN`,
`BOOTSTRAP`, TOTP, приватными ключами и настоящими письмами. Не прикладывайте `.env`.

---

## Короткий маршрут одной страницей

```powershell
# 1. Актуальный код проверочного прогона
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex-test
cd C:\Corvex-test
git log --oneline -4

# 2. Frontend (должен быть зелёным уже сейчас)
cd frontend
npm ci
npm test
npm run build
npm run dev   # открыть localhost:5173, демо, затем Ctrl+C
cd ..

# 3. Первый Java-гейт
mvn -B clean verify

# 4. Стенд и Docker-гейт
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\first-run.ps1
notepad .env  # домены company.local, bootstrap-пароль, протоколы пока false
powershell -ExecutionPolicy Bypass -File scripts\verify.ps1 -DockerBuild

# 5. Запуск
docker compose up -d
docker compose ps
# открыть https://mail.company.local (hosts: 127.0.0.1 mail.company.local)

# 6. Только после готовности стека и web-smoke: SMTP, затем IMAP/POP3 по шагам 8-9.
```

**Следующий рубеж проекта — закрыть оставшиеся сценарии G3, а не просто получить
первый `BUILD SUCCESS`.** При воспроизведении на новом SHA сначала добейтесь зелёного
Maven и стабильного `docker compose ps`: healthchecked-сервисы `healthy`, остальные `Up`.
После этого переходите к собственной почте, двухпользовательскому чату, архиву и
эксплуатационным проверкам по шагам выше.
