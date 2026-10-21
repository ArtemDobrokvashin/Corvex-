<div align="center">

<img src="frontend/public/corvex.svg" width="88" alt="Corvex logo" />

# Corvex

**Corporate mail & team chat that stays on your server.**<br />
**Корпоративная почта и сообщения, которые остаются дома.**<br />
<sub>Free &amp; open source (Apache-2.0): raise and run your own corporate mail service on your own servers.<br />
Бесплатный open-source проект (Apache-2.0): поднимите и настройте собственный корпоративный почтовый сервис на своих серверах.</sub>

[Русский](#русский) · [English](#english) · [Production status](#production-ready-пока-нет) · [Screenshots](#скриншоты) · [Quick start](#попробовать-за-2-минуты) · [First full test](docs/TESTING-ON-YOUR-PC.md) · [Contribute](CONTRIBUTING.md) · [Install](#установка-на-windows-server) · [Docs](#документация) · [Author](#автор-и-связь)

[![GitHub stars](https://img.shields.io/github/stars/ArtemDobrokvashin/Corvex-?style=social)](https://github.com/ArtemDobrokvashin/Corvex-/stargazers)
[![Last commit](https://img.shields.io/github/last-commit/ArtemDobrokvashin/Corvex-)](https://github.com/ArtemDobrokvashin/Corvex-/commits)
[![Not production-ready](https://img.shields.io/badge/production--ready-NO-b60205)](#production-ready-пока-нет)
![Stages 1-4](https://img.shields.io/badge/stages_1--4-code_written_runtime_unverified-d29922)
[![G0 passed](https://img.shields.io/badge/G0-frontend%20%2B%20static%20gates%20passed-2ea44f)](docs/RELEASE-DEPENDENCIES.md)
![RU EN](https://img.shields.io/badge/interface-RU%20%2F%20EN-3157d5)
![Chrome 109](https://img.shields.io/badge/browser-Chrome_109%2B-3157d5)
[![Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-2ea44f)](LICENSE)
![Free and open source](https://img.shields.io/badge/free-%26_open_source-2ea44f)
[![Telegram](https://img.shields.io/badge/Telegram-Artem__Dobrokvashin-26A5E4?logo=telegram&logoColor=white)](https://t.me/Artem_Dobrokvashin)

[![Corvex Mail](docs/screenshots/mail-ru.png)](docs/screenshots/mail-ru.png)

<sub>Real screenshot of the built frontend with clearly labelled mail demo data. The screenshot is UI evidence only—not proof that the Java/Docker backend has passed runtime acceptance.</sub>

</div>

> Corvex is an author-led open-source project currently developed by one enthusiast. Contributions are welcome for those who would like to join the work. / Corvex — авторский open-source проект, который сейчас ведёт один разработчик-энтузиаст. Если захотите присоединиться к разработке, милости просим: порядок участия описан в [`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## Русский

### Почта и команды — на вашем сервере, а не в чужом облаке

**Corvex** — бесплатный open-source проект (лицензия Apache-2.0) для тех, кто хочет **поднять и настроить собственный корпоративный почтовый сервис на своих серверах**. Это self-hosted платформа коммуникаций для организаций до 1000 сотрудников: собственный почтовый сервер, современный веб-клиент, командный чат, гибкая админ-панель, 2FA, мониторинг и резервное копирование в одном Docker-развёртывании.

Концепция Corvex проста: **переписка компании должна принадлежать компании, а инструмент для неё — быть свободным**. Исходный код открыт, подписки и платы за пользователя нет, скрытой телеметрии нет: вы скачиваете проект, разворачиваете его на своём Windows Server (или Linux с Docker), настраиваете домен, DNS и сертификаты по инструкциям — и почта с чатом живут на вашей инфраструктуре, а не в чужом облаке и не зависят от того, доступен ли внешний SaaS сегодня утром.

| Типичная боль | Как отвечает Corvex |
|---|---|
| Почта и чаты живут у внешнего провайдера | Данные, пользователи и аудит остаются на сервере организации |
| Старый парк Windows 7 / Chrome 109 нельзя просто выбросить | Отдельный legacy-бандл и адаптивный интерфейс для ПК, планшетов и телефонов |
| Админка либо «всё можно», либо «ничего нельзя» | 6 групп и 25 атомарных прав-чекбоксов; права суммируются по группам |
| Интерфейс переведён наполовину, ошибки API — на другом языке | Полные RU/EN: интерфейс, даты, числа, demo-данные и публичные ошибки API |
| Проект обещает «enterprise-ready», но прячет ограничения | Открытый аудит, честные ворота готовности и SMTP, выключенный до durable-доставки |

> [!CAUTION]
> **Corvex пока НЕ production-ready, но уже готов к первому честному внешнему тесту.** Stages 1–4 написаны в коде: identity/RBAC и админка, почтовый конвейер и webmail, IMAP/POP3 на Apache James, persistent-чат с файлами. Ворота **G0** пройдены: frontend обновлён и реально проверен (15/15 тестов, modern+legacy build, production audit 0), зависимости и Docker-образы приведены к зафиксированному baseline, статические проверки чисты. Следующий обязательный шаг — **G1**: первая компиляция Maven и запуск Spring/Docker на настоящем JDK-хосте. До G1–G5 это **pre-1.0 кандидат для изолированного стенда и разработки, а не замена рабочей почте**. Не направляйте production MX на Corvex, не переносите настоящие ящики и не включайте публичный SMTP, пока не пройдены ворота ниже и в [`docs/READINESS.md`](docs/READINESS.md).

### Попробовать за 2 минуты

Нужен только актуальный Node.js: 22.22.2+ LTS, 24.15+ или 26+ (см. `engines` в `frontend/package.json`):

```bash
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git
cd Corvex-/frontend
npm ci
npm run dev
```

Откройте `http://localhost:5173` и нажмите **«Посмотреть демо без сервера»**. Почта и чат покажут учебные данные с жёлтой плашкой. В полном стеке вход, администрирование, webmail, почтовые протоколы Stage 3 и чат Stage 4 подключены к серверным API в коде, но их совместный запуск ещё должен быть подтверждён Maven/Docker и клиентским smoke.

### Об авторе и участии в проекте

Corvex придумал и сейчас ведёт один разработчик-энтузиаст — **Artem Dobrokvashin**. Это личный open-source проект, а не продукт большой команды: код, архитектура и документация развиваются открыто и в обычном для такого проекта темпе.

Если идея собственной корпоративной почты вам близка и есть желание присоединиться, милости просим. Форматы участия обычные: код, тесты, проверка на своём стенде или почтовом клиенте, документация и переводы. С чего начать, описано в [`CONTRIBUTING.md`](CONTRIBUTING.md); маршрут полной первой проверки — в [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md). Для сообщения об ошибке достаточно SHA коммита, версий инструментов, выполненных команд и первой ошибки. Не прикладывайте `.env`, пароли, ключи и настоящие письма, а тесты проводите на отдельной VM и тестовом домене.

Скрытой телеметрии в Corvex нет. При желании информацию о своём стенде или внедрении можно добавить в [`ADOPTERS.md`](ADOPTERS.md).

### Скриншоты

Все кадры ниже — реальные скриншоты собранного фронтенда в Chromium. На самих кадрах почта и сообщения демонстрационные; скриншот интерфейса не является доказательством, что Java/Docker-контур уже работает на сервере.

<table>
  <tr>
    <td width="50%"><a href="docs/screenshots/login-ru.png"><img src="docs/screenshots/login-ru.png" alt="Экран входа Corvex на русском" /></a><br /><sub>Вход и RU/EN переключатель</sub></td>
    <td width="50%"><a href="docs/screenshots/chat-ru.png"><img src="docs/screenshots/chat-ru.png" alt="Сообщения Corvex с открытым emoji-picker" /></a><br /><sub>Сообщения, реакции и цветные Unicode-эмодзи</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/screenshots/admin-ru.png"><img src="docs/screenshots/admin-ru.png" alt="Администрирование пользователей Corvex на русском" /></a><br /><sub>Пользователи, группы, статусы и квоты</sub></td>
    <td width="50%" align="center"><a href="docs/screenshots/mail-mobile-ru.png"><img src="docs/screenshots/mail-mobile-ru.png" width="280" alt="Мобильная почта Corvex на русском" /></a><br /><sub>Мобильная почта</sub></td>
  </tr>
</table>

Как воспроизводимо переснять кадры: [`docs/screenshots/README.md`](docs/screenshots/README.md).

### Возможности

#### Реализовано в коде для реального бэкенда

- **Identity:** Argon2id, lockout после 5 неудач на 15 минут, JWT RS256 на 15 минут, вращающиеся refresh-сессии в HttpOnly `SameSite=Strict` cookie и детект повторного использования refresh-токена.
- **2FA и клиенты:** TOTP setup/confirm/disable, MFA-шаг при входе и пароли приложений; SMTP submission, POP3 и IMAP в коде используют их для протокольных логинов.
- **Администрирование:** пользователи, квоты, блокировка/отключение, 6 системных и пользовательские группы, 25 прав, аудит, настройки, health и базовая аналитика.
- **Защита от самоблокировки:** последнего `SUPER_ADMIN` нельзя лишить группы, заблокировать или отключить через API.
- **RU/EN до конца:** переключаются интерфейс, demo-данные, системные группы, формат дат/чисел/байт и `ProblemDetail` ошибки auth/admin по `Accept-Language`.
- **Эмодзи без картинок и CDN:** нативный Unicode picker, вставка в курсор, реакции и графемно-безопасная обрезка сложного текста.
- **Почта Stage 2:** durable SMTP spool, SpamAssassin → ClamAV → Maildir/CAS, серверный webmail, исходящая MX-очередь с retry/DSN, DKIM и submission 587 с AUTH+STARTTLS. Этот код ещё не принят живым запуском.
- **External Mail Connector:** старые ящики Mail.ru, Яндекса, Gmail, Outlook или custom-сервера подключаются к Corvex по IMAP/POP3: пресеты и проверка подключения, AES-256-GCM для пароля приложения, сбор новой почты, импорт архива папок с датами/флагами и дедупликацией, а также отправка от имени старого адреса через authenticated SMTP провайдера. Инструкция: [`docs/EXTERNAL-MAIL.md`](docs/EXTERNAL-MAIL.md). Как и остальная почта, функция ждёт живой Maven/Docker/клиентской приёмки.
- **Почтовые протоколы Stage 3:** внутренний протокольный API хранилища (папки/UID/MODSEQ/флаги/APPEND/COPY/QUOTA), POP3 (INBOX, стабильные UIDL, implicit TLS 995) и IMAP-движок Apache James 3.8.2 (MailboxManager/MessageManager поверх storage API, 143 STARTTLS/993, MOVE/QUOTA/CONDSTORE) написаны и выключены по умолчанию до Maven/клиентского smoke на JDK-хосте. Подписки LSUB пока живут в памяти процесса IMAP.
- **Чат Stage 4:** PostgreSQL-backed комнаты и сообщения, STOMP/RabbitMQ realtime, проверка членства на REST/SUBSCRIBE/SEND, реакции, закрепления, прочтения, typing/presence, поиск и файлы с синхронной ClamAV-проверкой. Код написан; нужен двухпользовательский runtime-smoke после G1/G2.
- **Современный frontend без отказа от старого парка:** React 19, Vite 8 и TypeScript 7 собираются в modern-бандл для актуальных браузеров и legacy-бандл для Chrome 109; Markdown проходит через единый DOMPurify-контур с тестами.

#### Следующие этапы и открытые ворота

- **G1–G3 — runtime-приёмка уже написанного:** Maven, Docker/Flyway/Spring, сквозная почта, Thunderbird/Outlook, двухпользовательский чат и backup/restore.
- **G4 — поддерживаемая платформа:** Spring Boot 4.1/Spring Cloud/Jackson 3, Apache James 3.9.x и замена EOL Promtail на Grafana Alloy отдельным апгрейдом после зелёного baseline.
- **Stage 5 и enterprise-hardening:** AD/LDAP, mTLS между сервисами, мгновенный отзыв JWT, строгая CSP, transactional outbox, персистентные LSUB-подписки, распределённый IDLE из SMTP/storage, keyword-флаги/ACL, CI и нагрузочные профили до 1000 сотрудников.

### Статус и roadmap

| Этап | Содержимое | Статус |
|---|---|---|
| 1 | Identity, пользователи, группы, RBAC, аудит, админка | 🟡 код написан; Maven/Docker runtime ещё не подтверждён |
| 2 | Durable SMTP-доставка, storage, spam/AV, поиск, исходящая очередь | 🟡 Stages 2A/2B написаны; JDK/Docker runtime ещё не подтверждён |
| 3 | IMAP/POP3 на Apache James, Outlook/Thunderbird | 🟡 код написан полностью (storage API + POP3 + IMAP-движок с MODSEQ); Maven/Docker и клиентский smoke ещё не подтверждены |
| 4 | Persistent realtime-чат, файлы, presence | 🟡 код написан (REST/STOMP/presence/файлы); Maven/Docker runtime ещё не подтверждён |
| G0 | Dependency baseline, frontend и статические ворота первого релиз-кандидата | ✅ пройдено в `main`: 15/15 frontend-тестов, modern+legacy build, production audit 0, зафиксированные исключения |
| G1–G5 | Maven, Docker, функциональная приёмка, поддерживаемая платформа, пилот | 🚧 следующий шаг — G1 на реальном JDK/Docker-хосте; подробная матрица в [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) |
| 5 | AD/LDAP, mTLS, production hardening | 📐 спроектировано; часть задач открыта для участников после зелёного baseline |

SMTP выключен по умолчанию на всех уровнях до живого Maven/Docker smoke. Даже с durable spool Corvex не должен ответить `250 OK`, пока сквозной путь письма не подтверждён на целевом хосте.

Последний слитый в `main` release-hardening baseline (PR #5) не притворяется runtime-доказательством, но убирает известные препятствия перед первым тестом: современный и реально проверенный frontend, финальный Spring Cloud 2023.0.6 для Boot 3.2 baseline, поддерживаемый SubEtha SMTP 7.2.2 и dnsjava 3.6.5, исправленный `james.version` в POP3, удалённые неиспользуемые зависимости, поддерживаемые Docker-пины и Dependabot, ограниченный сгруппированными minor/patch-обновлениями; major-версии автоматически не предлагаются. EOL-линия Boot 3.2 при этом не маскируется: она нужна только для первой компиляции, а поддерживаемая платформа остаётся обязательным G4.

### Production-ready? Пока нет

**Прямой ответ: нет. Corvex сегодня нельзя честно продавать или внедрять как production-ready продукт.** Это не означает, что репозиторий — пустой макет: Stages 1–4 действительно написаны как сервисный код. Но между «код написан и статически вычитан» и «системе можно доверить рабочую переписку» находится обязательная приёмка, которую ещё никто не выполнил на настоящем JDK/Docker-хосте.

Сейчас Corvex подходит для:

- изучения интерфейса и архитектуры;
- разработки и исправления первой сборки;
- изолированного тестового стенда без настоящих ящиков и без production MX;
- подготовки DNS/сертификатов на отдельном тестовом домене.

Сейчас Corvex **не подходит** для:

- замены рабочего корпоративного почтового сервера;
- переноса настоящих почтовых ящиков и переписки;
- публичного включения портов 25/587 как боевого сервиса;
- обещаний сотрудникам, что Outlook/Thunderbird уже работают как боевые клиенты (Stage 3 написан, но не принят на JDK/Docker), а чат Stage 4 уже проверен в бою: код написан, но runtime-приёмка ещё впереди.

#### Что уже подтверждено, а что нет

| Область | Подтверждено в текущем коммите | Не подтверждено |
|---|---|---|
| Frontend | `npm test`: 4 файла / 15 тестов; `npm run build`, включая legacy-бандл; production `npm audit` — 0 | настоящий Windows 7 + Chrome 109 и E2E против поднятого бэкенда |
| Зависимости | baseline, исключения и ворота зафиксированы в [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md); POP3 POM и поддерживаемый SMTP-движок исправлены до первого Maven-прогона | поддерживаемая Spring/James-платформа отдельным gate после первого зелёного Maven/Docker baseline |
| Java | синтаксис, контракты и зависимости вычитаны статически; unit-тесты написаны | `mvn` компиляция, выполнение Java-тестов, Spring-контексты, JaCoCo-гейты |
| Docker/инфраструктура | YAML разбирается; сервисы, сети, тома и healthcheck описаны | сборка образов, Flyway на чистом PostgreSQL, права на тома, все `healthy` без рестартов |
| Почта | код inbound/outbound/submission/DKIM/DSN и webmail API написан | письмо целиком SMTP → spam → AV → Maildir → webmail, внешняя доставка, проверка DKIM получателями, потери/дубли при сбоях |
| Эксплуатация | мониторинг, backup-скрипты и ворота спроектированы | восстановление из бэкапа, откат релиза, нагрузка на 1000 сотрудников, security-тесты и 72-часовой soak |

> Успешная сборка Docker-образа сама по себе тоже не равна пройденным Java-тестам: текущие Dockerfile собирают сервисы с `-DskipTests`. Поэтому `mvn verify` нужно запускать отдельно до или независимо от Compose.

#### Что может пойти не так — и как это исправлять

| Симптом при первой приёмке | Что это может означать | Как диагностировать и исправлять |
|---|---|---|
| Maven не компилирует сервис | несовпадение API SubEtha/Jakarta Mail/Spring AMQP, отсутствующий import/bean или ошибка типов, которую не видит синтаксический парсер | собрать один сервис без тестов, исправить **первую** ошибку компилятора, затем повторить; не «лечить» массовым понижением версий |
| Тесты проходят, но падает JaCoCo `verify` | покрытие ниже порога 70% в POM, а не обязательно сломана логика | добавить тесты на критичную логику; порог менять только отдельным осознанным решением, а не молча отключать перед релизом |
| Spring-контекст не стартует | дублирующийся/отсутствующий bean, AMQP-декларация, неверный env, недоступны PostgreSQL/Redis/RabbitMQ/Elasticsearch | читать полный cause-chain в `docker compose logs --tail 200 <сервис>`; исправить bean/config и добавить контекстный или интеграционный тест |
| Flyway или JPA `validate` падает | схема миграции расходится с entity/JPQL | добавить **новую** миграцию; уже применённую миграцию не редактировать. `docker compose down -v` допустим только на пустом стенде |
| `401/503` на internal API или после входа | разные/пустые `CORVEX_INTERNAL_API_TOKEN`, JWT-ключ не сохранён между рестартами, JWKS недоступен, рассинхронизировано время | проверить один и тот же секрет во всех сервисах, persistent PEM-ключ auth-service, NTP и логи auth/admin/gateway без публикации самих секретов |
| SMTP отвечает `451`, письмо не появляется или уходит в DLQ | storage/RabbitMQ/spam/AV недоступны, нет прав на spool/Maildir, превышена квота или событие не совпало с JSON-контрактом | проверить логи SMTP/storage/spam/AV, очереди и DLQ в RabbitMQ, место и владельца томов; не удалять spool вручную, пока не найден `rawRef` и причина |
| После рестарта письмо продублировалось или потерялось | at-least-once доставка AMQP и отсутствие полноценного transactional outbox между spool, RabbitMQ, Maildir и БД | воспроизвести остановку сервиса в каждой точке конвейера; до продакшена добавить/усилить идемпотентность или outbox и регрессионный тест именно на этот сбой |
| Webmail возвращает `403/422/413` при отправке | нет permission, отправитель не `ACTIVE`, домен не входит в `CORVEX_MAIL_DOMAINS`, плохой адрес или письмо больше лимита spool | проверить группы пользователя, статус ящика, локальные домены и размер MIME после Base64; права настраивать через админку, а не ослаблять проверки в коде |
| Submission не принимает AUTH/STARTTLS | нет сертификата, приватный ключ не PKCS#8 PEM, не проброшен 587, включён TOTP и используется основной пароль вместо app-password, `MAIL FROM` не принадлежит пользователю | убедиться по логу, что submission listener стартовал; проверить `/certs/fullchain.pem`, `/certs/privkey.pem`, app-password, права и совпадение envelope sender |
| Внешняя почта уходит в спам или bounce | нет PTR/rDNS, SPF не покрывает отправителя, публичный DKIM не совпадает с приватным ключом, DMARC alignment нарушен, провайдер закрыл исходящий 25 или IP без репутации | смотреть diagnostic в DSN и заголовки у получателя; начинать с SPF `~all` и DMARC `p=none`, опубликовать точный `corvex1._domainkey`, настроить PTR и mail-tester ≥ 9/10 до ужесточения политики |
| На Windows 7 интерфейс не работает как на скриншотах | браузеру отдан modern-бандл вместо legacy, кэш nginx/браузера или отсутствует нужный polyfill | проверить заголовки и фактически загружаемый `*-legacy*.js`, затем повторить smoke на реальной Windows 7 + Chrome 109; скриншот из современного Chromium этот гейт не заменяет |

#### Как довести Corvex до production-ready

- [ ] **0. Не открывать прод раньше времени.** Держать `CORVEX_SMTP_ENABLED=false`, не менять рабочие MX и работать на отдельной VM/тестовом поддомене.
- [ ] **1. Зелёный Maven по всем 12 сервисам.** На хосте с JDK 17 и Maven 3.9 выполнить из корня репозитория (корневой `pom.xml` — агрегатор всех сервисов):

  ```powershell
  mvn -B clean verify
  ```

  Пункты 1–3 одной командой также выполняет `scripts\verify.ps1` (Windows Server) или `scripts/verify.sh` (Linux); с флагом `-DockerBuild` / `--docker-build` дополнительно собираются все Docker-образы.

- [ ] **2. Зелёный frontend именно релизного коммита:** `npm ci`, `npm test`, `npm run build`, `npm audit --omit=dev --audit-level=moderate`. Для текущего `main` этот G0 уже фактически пройден, но каждый релизный кандидат повторяет его без сокращений.
- [ ] **3. Чистый Compose-подъём.** `docker compose config`, сборка всех образов, первый старт на пустых томах, все сервисы `healthy`, Flyway применился, нет restart loop и необъяснённых DLQ.
- [ ] **4. Identity/admin smoke.** Bootstrap-админ, смена пароля, второй пользователь, группы/права, lockout, TOTP, app-password, аудит и запрет действий над чужими данными.
- [ ] **5. Почтовый smoke на тестовом домене.** Внешнее письмо → INBOX; локальное webmail-письмо; внешнее письмо на контролируемый MX; вложение; Bcc не виден другим получателям; письмо переживает рестарт контейнеров.
- [ ] **6. Негативные почтовые сценарии.** Несуществующий локальный получатель — `550`; open relay запрещён; storage/Rabbit недоступны — `451`, а не ложный `250 OK`; временный `4xx` уходит в retry; постоянный `5xx` создаёт DSN; повтор события не создаёт дубль письма.
- [ ] **7. Submission и доставляемость.** STARTTLS обязателен, app-password работает, чужой `MAIL FROM` отклоняется `553`, DKIM/SPF/DMARC проходят у внешнего получателя, PTR совпадает с SMTP hostname, mail-tester ≥ 9/10.
- [ ] **8. Smoke чата Stage 4.** Два пользователя: direct и групповой диалог, история после рестарта контейнеров, не-участник не читает комнату и не скачивает её файл, повторная отправка с тем же `clientMessageId` не плодит дубль, реакции/закрепления/прочтения видны обеим сторонам, файл EICAR отклоняется антивирусом, STOMP продолжает работать после обновления JWT.
- [ ] **8A. Клиентский smoke Stage 3 (IMAP/POP3).** Сначала только на стенде: сгенерировать PKCS12 (`openssl pkcs12 -export -inkey privkey.pem -in fullchain.pem -out infra/certs/imap.p12`, аналогично `pop3.p12`), задать пароли keystore в `.env`, включить `CORVEX_IMAP_ENABLED=true`/`CORVEX_POP3_ENABLED=true` и пересоздать только протокольные сервисы. Thunderbird/Outlook: LOGIN полным e-mail (при 2FA — app-password), LIST видит системные и вложенные папки, SELECT/FETCH открывают письмо и вложение, отправка через submission сохраняет копию в Sent, MOVE в Trash + EXPUNGE удаляет навсегда, поиск по теме находит письмо, POP3 995 забирает INBOX и DELE без QUIT ничего не удаляет. Без сертификата допустим только локальный plain-тест 143/110 на изолированной VM.
- [ ] **9. Эксплуатационная приёмка.** Backup (`scripts/backup.ps1`) → восстановление (`scripts/restore.ps1`) на чистой VM, откат на предыдущие образы, алерт при остановке сервиса, ротация сертификата, нагрузочные цели из [`docs/10-test-strategy.md`](docs/10-test-strategy.md), security-проход и реальная Windows 7 + Chrome 109.
- [ ] **9A. Поддерживаемая dependency-платформа (G4).** После первого зелёного baseline отдельным апгрейдом перейти на Spring Boot 4.1/Spring Cloud/Jackson 3 и James 3.9.x, заменить Promtail на Alloy и повторить Maven, Docker, почтовые и чат-smoke без сокращений.
- [ ] **10. Пилот, а не сразу «прод для всех».** Минимум 72 часа на тестовом/пилотном домене с мониторингом очередей, DLQ, диска, bounce-rate и жалоб пользователей; только после этого переносить ограниченную группу ящиков.

Даже после пунктов 1–10 корректный статус будет сначала **«pilot-ready для ограниченного webmail/SMTP-контура»**, а не production-ready всей платформы: для полного обещания Corvex ещё нужны runtime-подтверждение уже написанных Stage 3 (IMAP/POP3) и Stage 4 (сохраняемый чат и файлы), а также Stage 5 (AD/LDAP и production hardening), либо честное сужение заявленного объёма продукта.

Если первая сборка упала, для issue приложите SHA коммита, первую ошибку Maven или полный стек Spring, `docker compose ps` и хвост логов соответствующего сервиса. **Не прикладывайте** `.env`, пароли, internal token, приватные ключи и настоящие письма.

### Установка на Windows Server

> Все завершённые этапы уже слиты в `main`; для тестирования используйте именно эту ветку. Пошаговая приёмка: [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md).

```powershell
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex

.\scripts\first-run.ps1     # уникальные секреты, JWT-ключ, internal API token, пилотный TLS, .env
notepad .env                # CORVEX_DOMAIN, CORVEX_MAIL_DOMAIN, получатели алертов

docker compose config
docker compose up -d --build                 # 12 микросервисов + инфраструктура
docker compose --profile monitoring up -d    # Prometheus, Grafana, Loki, Alertmanager
docker compose ps                            # первый настоящий smoke-gate: все healthy
```

Откройте `https://mail.<ваш-домен>`. Первый пользователь — `admin`; пароль задаётся `CORVEX_BOOTSTRAP_ADMIN_PASSWORD` или один раз печатается в лог:

```powershell
docker compose logs auth-service | Select-String BOOTSTRAP
```

Полная инструкция: [`docs/INSTALL.md`](docs/INSTALL.md) — Hyper-V/Docker, брандмауэр, TLS, DNS, сертификаты, бэкапы и траблшутинг.

### Архитектура

12 сервисов Java 17 / Spring Boot за единым gateway:

- `auth-service` владеет учётками, паролями, TOTP и сессиями;
- `admin-service` — группами, правами, настройками и аудитом;
- `web-gateway` проверяет JWT и ограничивает частоту запросов;
- `mail-smtp`, `mail-imap`, `mail-pop3` отделяют протоколы от бизнес-логики;
- `mail-storage` — Maildir и вложения по SHA-256 с дедупликацией;
- `mail-spam`, `mail-antivirus` — тонкие клиенты SpamAssassin и ClamAV;
- `chat-service`, `chat-file-service` — STOMP и файлы;
- `notification-service` превращает Alertmanager webhook в email администраторам.

Данные: PostgreSQL 16 (database-per-service), Redis, RabbitMQ, Elasticsearch, Maildir + CAS. Наблюдаемость: Prometheus, Grafana, Loki. Один `docker-compose.yml`: 25 контейнеров, 4 сети, 12 томов.

Диаграммы и ADR: [`docs/01-architecture.md`](docs/01-architecture.md).

### Безопасность и приватность

- Refresh-токен не хранится в открытом виде: в PostgreSQL лежит SHA-256 от 32 случайных байт.
- Внутренние auth/admin API не маршрутизируются наружу и требуют `X-Corvex-Internal-Token`; без секрета — fail-closed `503`.
- nginx — единственный доверенный edge для клиентского IP; поддельный первый `X-Forwarded-For` не попадает в rate-limit и аудит.
- Повторный TOTP setup не заменяет уже включённую 2FA без текущего кода.
- Настройки валидируются на сервере и пишутся одной транзакцией.
- Markdown санитизируется; внешние изображения в чате запрещены как tracking pixels.
- **Нет скрытой телеметрии, рекламных трекеров и license phone-home.** Если когда-нибудь появится проверка обновлений, она должна быть явной, документированной и отключаемой.

Полный аудит: [`docs/12-full-audit-2026-10.md`](docs/12-full-audit-2026-10.md).

### Документация

| Документ | Содержание |
|---|---|
| [`docs/TECHNICAL_DESIGN.md`](docs/TECHNICAL_DESIGN.md) | мастер-документ технического проекта |
| [`docs/12-full-audit-2026-10.md`](docs/12-full-audit-2026-10.md) | полный аудит фронтенда, бэкенда и инфраструктуры |
| [`docs/READINESS.md`](docs/READINESS.md) | честный статус по компонентам и ворота пилота |
| [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) | зафиксированные версии, осознанные исключения и порядок G0–G5 |
| [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md) | пошаговая первая приёмка на вашем ПК: от frontend до Maven, Docker и почтовых клиентов |
| [`docs/EXTERNAL-MAIL.md`](docs/EXTERNAL-MAIL.md) | подключение Mail.ru, Яндекса и других старых ящиков: сбор, миграция архива и send-as |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | участие в разработке: код, тесты, документация, переводы и требования к PR |
| [`docs/INSTALL.md`](docs/INSTALL.md) | установка и эксплуатация; есть [`INSTALL.en.md`](docs/INSTALL.en.md) |
| [`docs/11-modern-design-analysis.md`](docs/11-modern-design-analysis.md) | Gmail/Outlook/Slack/Superhuman/Linear и дизайн-система Quiet Work |
| [`docs/api/*.yaml`](docs/api/) | OpenAPI: auth, mail, chat, admin |
| [`ADOPTERS.md`](ADOPTERS.md) | кто использует Corvex и как добавить организацию |
| [`SUPPORT.md`](SUPPORT.md) | куда писать о багах, безопасности, донатах и коммерческой помощи |

### Автор и связь

Автор Corvex — **Artem Dobrokvashin**: [Telegram](https://t.me/Artem_Dobrokvashin). По вопросам проекта, внедрений, планирования миграций, обучения администраторов и заказных интеграций можно связаться напрямую. Вопросы добровольных пожертвований также обсуждаются только напрямую с автором; платёжные реквизиты в репозитории не публикуются.

Corvex бесплатен и открыт под Apache-2.0, и ядро для собственного сервера таким и останется. Продукт не требует подписки, платы за пользователя или «активации».

### Лицензия

Corvex распространяется под лицензией **Apache License 2.0** — полный текст в файле [`LICENSE`](LICENSE), атрибуция и перечень сторонних компонентов — в [`NOTICE`](NOTICE). Это значит: бесплатное развёртывание на собственном сервере без подписки и платы за пользователя, право модифицировать, распространять и использовать в коммерческих целях при соблюдении условий Apache-2.0. Вклад в проект принимается под той же лицензией (см. [`CONTRIBUTING.md`](CONTRIBUTING.md)).

---

## English

### Mail and team conversations that stay home

**Corvex** is a free open-source project (Apache-2.0) for people who want to **raise and run their own corporate mail service on their own servers**. It is a self-hosted communication platform for organizations of up to 1,000 employees: an own mail server, a modern webmail client, team chat, a flexible administration panel, 2FA, monitoring, and backups in one Docker deployment.

The concept is deliberately simple: **a company's correspondence should belong to the company, and the tool for it should be free**. The source code is open, there is no subscription or per-seat fee and no hidden telemetry: you download the project, deploy it on your Windows Server (or Linux with Docker), configure your domain, DNS, and certificates with the supplied guides — and mail and chat live on your infrastructure instead of a third-party cloud, independent of somebody else's SaaS being up this morning.

| Common pain | Corvex answer |
|---|---|
| Mail and chat live with an external provider | Data, users, and audit stay on your organization's server |
| The Windows 7 / Chrome 109 fleet cannot simply be discarded | A dedicated legacy bundle plus responsive phone/tablet layouts |
| Administration is either “everything” or “nothing” | 6 groups and 25 atomic checkbox permissions, unioned across groups |
| Half the UI is translated and API errors are not | Complete RU/EN across UI, dates, numbers, demo data, and public API errors |
| “Enterprise-ready” hides the fine print | A public audit, explicit readiness gates, and SMTP disabled until durable delivery |

> [!CAUTION]
> **Corvex is NOT production-ready yet—but it is ready for its first honest outside test.** Stages 1–4 are written in code: identity/RBAC and Administration, the mail pipeline and webmail, Apache James IMAP/POP3, and persistent chat with files. Gate **G0** has passed: the frontend was upgraded and actually verified (15/15 tests, modern+legacy build, production audit 0), dependencies and Docker images were moved to a recorded baseline, and static checks are clean. The next required step is **G1**: the first Maven compilation and Spring/Docker startup on a real JDK host. Until G1–G5 pass, treat this repository as a **pre-1.0 test-bench and development candidate, not a replacement for a working mail server**. Do not point production MX records at Corvex, migrate real mailboxes, or expose SMTP publicly until the gates below and in [`docs/READINESS.md`](docs/READINESS.md) pass.

### Try it in 2 minutes

Only a current Node.js patch line is required: 22.22.2+ LTS, 24.15+, or 26+ (see `engines` in `frontend/package.json`):

```bash
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git
cd Corvex-/frontend
npm ci
npm run dev
```

Open `http://localhost:5173` and click **“View the demo without a server.”** Mail and Messages show clearly labelled sample data. In the full stack, sign-in, Administration, webmail, Stage 3 mail protocols, and Stage 4 chat are wired to server APIs in code, but that combined runtime still has to pass Maven/Docker and mail-client acceptance.

### About the author and joining the project

Corvex was created and is currently developed by one enthusiast, **Artem Dobrokvashin**. It is a personal open-source project rather than the product of a large team: the code, architecture, and documentation evolve in the open at a sustainable individual pace.

If the idea of running your own corporate mail service appeals to you and you would like to take part, you are welcome to do so. The usual forms of participation apply: code, tests, checks on your own bench or mail client, documentation, and translations. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md); the full first-test route is described in [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md) (Russian). A useful bug report contains the commit SHA, tool versions, commands, and the first error. Never attach `.env`, passwords, keys, or real mail, and use a separate VM and test domain.

Corvex contains no hidden telemetry. If you wish, you can record a bench or deployment in [`ADOPTERS.md`](ADOPTERS.md).

### Screenshots

All frames below are real captures of the built frontend in Chromium. The captures themselves show demo mail and messages; a UI screenshot is not evidence that the Java/Docker backend already runs successfully.

<table>
  <tr>
    <td width="50%"><a href="docs/screenshots/login-en.png"><img src="docs/screenshots/login-en.png" alt="Corvex sign-in in English" /></a><br /><sub>Sign-in with the RU/EN switch</sub></td>
    <td width="50%"><a href="docs/screenshots/mail-en.png"><img src="docs/screenshots/mail-en.png" alt="Corvex Mail in English" /></a><br /><sub>Mail in the Quiet Work three-pane layout</sub></td>
  </tr>
  <tr>
    <td width="50%"><a href="docs/screenshots/chat-en.png"><img src="docs/screenshots/chat-en.png" alt="Corvex Messages with the emoji picker open in English" /></a><br /><sub>Messages, reactions, and native color emoji</sub></td>
    <td width="50%"><a href="docs/screenshots/admin-en.png"><img src="docs/screenshots/admin-en.png" alt="Corvex Administration users in English" /></a><br /><sub>Users, groups, statuses, and quotas</sub></td>
  </tr>
</table>

Reproduce the captures with [`docs/screenshots/README.md`](docs/screenshots/README.md).

### Features

#### Implemented in code for the real backend

- **Identity:** Argon2id, 15-minute lockout after 5 failures, 15-minute RS256 JWTs, rotating refresh sessions in an HttpOnly `SameSite=Strict` cookie, and refresh-token reuse detection.
- **2FA and clients:** TOTP setup/confirm/disable, an MFA sign-in step, and app passwords; SMTP submission, POP3, and IMAP use them for protocol logins in code.
- **Administration:** users, quotas, block/disable, 6 system plus custom groups, 25 permissions, audit, settings, service health, and basic sign-in analytics.
- **No accidental lock-out:** the last `SUPER_ADMIN` cannot lose membership, be blocked, or be disabled through the API.
- **RU/EN end to end:** UI, demo data, system groups, dates/numbers/bytes, and auth/admin `ProblemDetail` errors localized by `Accept-Language`.
- **Emoji without images or a CDN:** a native Unicode picker, cursor-aware insertion, reactions, and grapheme-safe text handling.
- **Stage 2 mail:** durable SMTP spooling, SpamAssassin → ClamAV → Maildir/CAS, server-backed webmail, an outbound MX queue with retry/DSN, DKIM, and submission on port 587 with AUTH+STARTTLS. This code has not yet passed live runtime acceptance.
- **External Mail Connector:** legacy Mail.ru, Yandex, Gmail, Outlook, or custom-server mailboxes can be attached to Corvex over IMAP/POP3: presets and connection tests, AES-256-GCM app-password storage, scheduled collection, all-folder archive import with dates/flags and deduplication, plus send-as through the provider's authenticated SMTP. Guide: [`docs/EXTERNAL-MAIL.md`](docs/EXTERNAL-MAIL.md). Like the rest of mail, it still needs live Maven/Docker/client acceptance.
- **Stage 3 mail protocols:** the storage-side internal protocol API (folders/UIDs/MODSEQ/flags/APPEND/COPY/QUOTA), POP3 (INBOX, stable UIDLs, implicit TLS 995), and an Apache James 3.8.2 IMAP engine (MailboxManager/MessageManager over the storage API, 143 STARTTLS/993, MOVE/QUOTA/CONDSTORE) are written and disabled by default until the Maven/client smoke passes on a JDK host. LSUB subscriptions currently live in the IMAP process memory.
- **Stage 4 chat:** PostgreSQL-backed rooms and messages, STOMP/RabbitMQ realtime, membership checks on REST/SUBSCRIBE/SEND, reactions, pins, read receipts, typing/presence, search, and files with synchronous ClamAV scanning. The code exists; it needs a two-user runtime smoke after G1/G2.
- **A modern frontend that does not abandon the old fleet:** React 19, Vite 8, and TypeScript 7 produce a modern bundle for current browsers and a legacy bundle for Chrome 109; chat Markdown passes through one tested DOMPurify path.

#### Next stages and open gates

- **G1–G3 — runtime acceptance of code that already exists:** Maven, Docker/Flyway/Spring, end-to-end mail, Thunderbird/Outlook, two-user chat, and backup/restore.
- **G4 — the supported platform:** Spring Boot 4.1/Spring Cloud/Jackson 3, Apache James 3.9.x, and Grafana/Alloy instead of EOL Promtail, as a separate upgrade after the baseline is green.
- **Stage 5 and enterprise hardening:** AD/LDAP, service-to-service mTLS, instant JWT revocation, strict CSP, a transactional outbox, persistent LSUB subscriptions, distributed IDLE from SMTP/storage, keyword flags/ACL, CI, and load profiles for 1,000 employees.

### Status and roadmap

| Stage | Scope | Status |
|---|---|---|
| 1 | Identity, users, groups, RBAC, audit, administration | 🟡 code written; Maven/Docker runtime not yet proven |
| 2 | Durable SMTP delivery, storage, spam/AV, search, outbound queue | 🟡 Stages 2A/2B written; JDK/Docker runtime not yet proven |
| 3 | Apache James IMAP/POP3, Outlook/Thunderbird | 🟡 code complete (storage protocol API + POP3 + IMAP engine with MODSEQ); Maven/Docker and mail-client smoke not yet proven |
| 4 | Persistent realtime chat, files, presence | 🟡 code written (REST/STOMP/presence/files); Maven/Docker runtime not yet proven |
| G0 | Dependency baseline, frontend, and static gates for the first release candidate | ✅ passed in `main`: 15/15 frontend tests, modern+legacy build, production audit 0, recorded exceptions |
| G1–G5 | Maven, Docker, functional acceptance, supported platform, pilot | 🚧 next is G1 on a real JDK/Docker host; see [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) |
| 5 | AD/LDAP, mTLS, production hardening | 📐 designed; parts are open to contributors after the baseline is green |

SMTP is disabled by default at every layer until the live Maven/Docker smoke passes. Even with a durable spool, Corvex must not say `250 OK` until the end-to-end path is proven on a target host.

The latest release-hardening baseline merged into `main` (PR #5) does not pretend to be runtime proof, but it removes known obstacles before the first test: a modern, actually verified frontend; final Spring Cloud 2023.0.6 for the Boot 3.2 baseline; maintained SubEtha SMTP 7.2.2 and dnsjava 3.6.5; the missing POP3 `james.version`; unused dependencies removed; supported Docker pins; and Dependabot limited to grouped minor/patch updates, with major versions not proposed automatically. The EOL Boot 3.2 line is not disguised: it exists only for the first compilation, while the supported platform remains mandatory G4 work.

### Production-ready? Not yet

**The direct answer is no. Corvex must not honestly be sold or deployed as a production-ready product today.** That does not make the repository an empty mock-up: Stages 1–4 are real service code. The missing step is the acceptance work between “code is written and statically reviewed” and “this system may be trusted with working correspondence.” Nobody has completed that step on a real JDK/Docker host yet.

Corvex is currently appropriate for:

- evaluating the interface and architecture;
- development and first-build fixes;
- an isolated test bench with no real mailboxes and no production MX records;
- preparing DNS and certificates on a separate test domain.

Corvex is currently **not** appropriate for:

- replacing a working corporate mail server;
- migrating real mailboxes or correspondence;
- exposing ports 25/587 as a production service;
- promising employees production-ready Outlook/Thunderbird access (Stage 3 is written but not yet accepted on JDK/Docker) or a battle-proven Stage 4 chat: the code is written, but runtime acceptance is still ahead.

#### What is proven—and what is not

| Area | Proven in this commit | Not proven |
|---|---|---|
| Frontend | `npm test`: 4 files / 15 tests; `npm run build`, including the legacy bundle; production `npm audit` is 0 | real Windows 7 + Chrome 109 and E2E against a running backend |
| Dependencies | the baseline, exceptions, and gates are recorded in [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md); the POP3 POM and maintained SMTP engine were fixed before the first Maven run | the supported Spring/James platform as a separate gate after the first green Maven/Docker baseline |
| Java | syntax, contracts, and dependencies statically reviewed; unit tests written | Maven compilation, Java test execution, Spring contexts, JaCoCo gates |
| Docker/infrastructure | YAML parses; services, networks, volumes, and healthchecks are described | image builds, Flyway on clean PostgreSQL, volume permissions, every service staying `healthy` |
| Mail | inbound/outbound/submission/DKIM/DSN and webmail API code exists | complete SMTP → spam → AV → Maildir → webmail delivery, external delivery, recipient-side DKIM validation, loss/duplicates under failure |
| Operations | monitoring, backup scripts, and gates are designed | backup restore, release rollback, 1,000-user load, security testing, and a 72-hour soak |

> A successful Docker image build is not the same as passing Java tests: the current Dockerfiles package services with `-DskipTests`. Run `mvn verify` separately before or independently of Compose.

#### What can go wrong—and how to fix it

| First-acceptance symptom | Likely meaning | Diagnosis and fix |
|---|---|---|
| Maven cannot compile a service | a SubEtha/Jakarta Mail/Spring AMQP API mismatch, missing import/bean, or a type error invisible to a syntax parser | build one service without tests, fix the **first** compiler error, and repeat; do not “fix” it by downgrading a stack of versions at once |
| Tests pass but JaCoCo `verify` fails | coverage is below the 70% POM threshold; the logic is not necessarily broken | add tests around critical behavior; change the threshold only as an explicit decision, never silently before a release |
| A Spring context will not start | duplicate/missing bean, AMQP declaration problem, bad environment, or unavailable PostgreSQL/Redis/RabbitMQ/Elasticsearch | read the full cause chain in `docker compose logs --tail 200 <service>`, fix the bean/config, and add a context or integration test |
| Flyway or JPA `validate` fails | a migration schema differs from entities/JPQL | add a **new** migration; never edit a migration that has already run. `docker compose down -v` is only acceptable on an empty test bench |
| `401/503` from internal APIs or after sign-in | missing/mismatched `CORVEX_INTERNAL_API_TOKEN`, a non-persistent JWT key, unavailable JWKS, or clock skew | use one token everywhere, persist the auth-service PEM key, synchronize NTP, and inspect auth/admin/gateway logs without publishing secrets |
| SMTP returns `451`, mail is missing, or an event reaches a DLQ | storage/RabbitMQ/spam/AV is down, spool/Maildir permissions are wrong, quota is exceeded, or a JSON event does not match its contract | inspect SMTP/storage/spam/AV logs, RabbitMQ queues/DLQs, free space, and volume ownership; do not delete spool files manually until the `rawRef` and root cause are known |
| Mail is duplicated or lost after a restart | AMQP at-least-once delivery plus no complete transactional outbox across spool, RabbitMQ, Maildir, and PostgreSQL | stop each pipeline service at every handoff and reproduce it; strengthen idempotency or add an outbox plus a regression test before production |
| Webmail send returns `403/422/413` | missing permission, sender is not `ACTIVE`, the domain is absent from `CORVEX_MAIL_DOMAINS`, an address is invalid, or the MIME message exceeds the spool limit | check the user's groups, mailbox status, local domains, and MIME size after Base64; change permissions in Administration rather than weakening server checks |
| Submission rejects AUTH/STARTTLS | certificate missing, private key is not unencrypted PKCS#8 PEM, port 587 is not mapped, TOTP requires an app password instead of the main password, or `MAIL FROM` is not owned by the user | confirm from logs that the submission listener started; verify `/certs/fullchain.pem`, `/certs/privkey.pem`, app password, permissions, and envelope sender |
| External mail lands in spam or bounces | missing PTR/rDNS, SPF does not cover the sender, the published DKIM key does not match the private key, DMARC alignment fails, outbound port 25 is blocked, or the IP has no reputation | read the DSN diagnostic and recipient-side headers; start with SPF `~all` and DMARC `p=none`, publish the exact `corvex1._domainkey`, configure PTR, and reach mail-tester ≥ 9/10 before tightening policy |
| The UI fails on Windows 7 despite the screenshots | the browser received the modern bundle instead of legacy assets, stale nginx/browser cache, or a missing polyfill | verify that a `*-legacy*.js` asset is actually loaded, then repeat the smoke on real Windows 7 + Chrome 109; a modern Chromium screenshot does not satisfy this gate |

#### Path to production-ready

- [ ] **0. Keep production closed.** Keep `CORVEX_SMTP_ENABLED=false`, do not change working MX records, and use a separate VM/test subdomain.
- [ ] **1. Make Maven green for all 12 services** on JDK 17 + Maven 3.9, from the repository root (the root `pom.xml` aggregates every service):

  ```powershell
  mvn -B clean verify
  ```

  `scripts\verify.ps1` (Windows Server) or `scripts/verify.sh` (Linux) runs gates 1–3 as one command; add `-DockerBuild` / `--docker-build` to also build every Docker image.

- [ ] **2. Make the release commit's frontend green:** `npm ci`, `npm test`, `npm run build`, `npm audit --omit=dev --audit-level=moderate`. Current `main` has passed this G0, but every release candidate repeats it without shortcuts.
- [ ] **3. Pass a clean Compose launch.** Validate config, build every image, start on empty volumes, apply Flyway, reach all-`healthy`, and eliminate restart loops and unexplained DLQ messages.
- [ ] **4. Pass identity/admin smoke.** Bootstrap admin, password change, a second user, groups/permissions, lockout, TOTP, app passwords, audit, and cross-user data isolation.
- [ ] **5. Pass mail smoke on a test domain.** External mail reaches INBOX; local webmail works; external delivery reaches a controlled MX; attachments work; Bcc is not exposed; mail survives container restarts.
- [ ] **6. Pass negative mail cases.** Unknown local recipient returns `550`; open relay is denied; unavailable storage/Rabbit returns `451`, not a false `250 OK`; temporary `4xx` retries; permanent `5xx` creates a DSN; redelivered events do not duplicate mail.
- [ ] **7. Pass submission and deliverability.** STARTTLS is mandatory, app passwords work, a foreign `MAIL FROM` returns `553`, DKIM/SPF/DMARC pass at an external recipient, PTR matches the SMTP hostname, and mail-tester scores at least 9/10.
- [ ] **8. Pass the Stage 4 chat smoke.** Two users in a direct and a group dialog; history survives container restarts; a non-member can neither read the room nor download its file; resending with the same `clientMessageId` creates no duplicate; reactions/pins/read receipts are visible to both sides; an EICAR file is rejected by the antivirus; STOMP keeps working across a JWT refresh.
- [ ] **8A. Pass the Stage 3 mail-client smoke (IMAP/POP3).** Bench only: create PKCS12 bundles (`openssl pkcs12 -export -inkey privkey.pem -in fullchain.pem -out infra/certs/imap.p12`, likewise `pop3.p12`), set the keystore passwords in `.env`, enable `CORVEX_IMAP_ENABLED=true`/`CORVEX_POP3_ENABLED=true`, and recreate only the protocol services. In Thunderbird/Outlook: log in with the full e-mail (app password when 2FA is on), LIST shows system and nested folders, SELECT/FETCH opens a message and attachment, sending via submission stores a Sent copy, MOVE to Trash + EXPUNGE deletes forever, subject search finds the message, and POP3 995 fetches INBOX while DELE without QUIT deletes nothing. Without a certificate, plain 143/110 is acceptable only as a local test on an isolated VM.
- [ ] **9. Pass operational acceptance.** Backup (`scripts/backup.ps1`) → restore (`scripts/restore.ps1`) on a clean VM, rollback to previous images, service-down alerting, certificate rotation, the load targets in [`docs/10-test-strategy.md`](docs/10-test-strategy.md), a security pass, and real Windows 7 + Chrome 109.
- [ ] **9A. Move to the supported dependency platform (G4).** After the first green baseline, separately upgrade to Spring Boot 4.1/Spring Cloud/Jackson 3 and James 3.9.x, replace Promtail with Alloy, and repeat the Maven, Docker, mail, and chat smoke tests without shortcuts.
- [ ] **10. Pilot before production.** Run at least 72 hours on a test/pilot domain while monitoring queues, DLQs, disk, bounce rate, and user reports; only then migrate a limited mailbox group.

Even after steps 1–10, the honest first label is **“pilot-ready for a limited webmail/SMTP scope,”** not production-ready for the whole platform. The full Corvex promise still needs runtime proof of the already-written Stage 3 (IMAP/POP3) and Stage 4 (persistent chat/files), plus Stage 5 (AD/LDAP and production hardening), unless the advertised product scope is deliberately narrowed.

If the first build fails, attach the commit SHA, the first Maven error or complete Spring stack trace, `docker compose ps`, and the relevant service log tail to the issue. **Never attach** `.env`, passwords, the internal token, private keys, or real mail.

### Install on Windows Server

> All completed stages are merged into `main`; use that branch for testing. Step-by-step acceptance guide: [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md) (Russian).

```powershell
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex

.\scripts\first-run.ps1     # unique secrets, JWT key, internal API token, pilot TLS, .env
notepad .env                # CORVEX_DOMAIN, CORVEX_MAIL_DOMAIN, alert recipients

docker compose config
docker compose up -d --build                 # 12 microservices + infrastructure
docker compose --profile monitoring up -d    # Prometheus, Grafana, Loki, Alertmanager
docker compose ps                            # first real smoke gate: everything healthy
```

Open `https://mail.<your-domain>`. The first user is `admin`; set `CORVEX_BOOTSTRAP_ADMIN_PASSWORD` or read the one-time password from the log:

```powershell
docker compose logs auth-service | Select-String BOOTSTRAP
```

Complete guide: [`docs/INSTALL.en.md`](docs/INSTALL.en.md).

### Architecture

Twelve Java 17 / Spring Boot services sit behind one gateway. Auth owns accounts, passwords, TOTP, and sessions; Admin owns groups, permissions, settings, and audit; Gateway verifies JWTs and rate limits. Mail protocols are separated from Maildir/SHA-256 storage, spam uses SpamAssassin, antivirus uses ClamAV, chat uses STOMP/RabbitMQ, and notifications turn Alertmanager webhooks into administrator email.

Data lives in PostgreSQL 16 (database-per-service), Redis, RabbitMQ, Elasticsearch, and Maildir + CAS. Observability uses Prometheus, Grafana, and Loki. One `docker-compose.yml` defines 25 containers, 4 networks, and 12 volumes.

Diagrams and ADRs: [`docs/01-architecture.md`](docs/01-architecture.md).

### Security and privacy

- Refresh tokens are never stored raw: PostgreSQL keeps SHA-256 of 32 random bytes.
- Internal auth/admin APIs are not routed by the gateway and require `X-Corvex-Internal-Token`; with no secret they fail closed with `503`.
- nginx is the only trusted edge for client IP; a spoofed first `X-Forwarded-For` cannot poison rate limits or audit.
- Re-running TOTP setup cannot replace already enabled 2FA without a current code.
- Admin settings are validated on the server and written in one transaction.
- Chat Markdown is sanitized and external images are forbidden as tracking pixels.
- **No hidden telemetry, advertising trackers, or license phone-home.** Any future update check must be explicit, documented, and disableable.

Full audit: [`docs/12-full-audit-2026-10.md`](docs/12-full-audit-2026-10.md).

### Documentation

| Document | Contents |
|---|---|
| [`docs/TECHNICAL_DESIGN.md`](docs/TECHNICAL_DESIGN.md) | master technical design |
| [`docs/12-full-audit-2026-10.md`](docs/12-full-audit-2026-10.md) | full frontend/backend/infrastructure audit |
| [`docs/READINESS.md`](docs/READINESS.md) | honest component status and pilot gates |
| [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) | recorded versions, explicit exceptions, and the G0–G5 order (Russian) |
| [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md) | step-by-step first acceptance on your PC: frontend, Maven, Docker, and mail clients (Russian) |
| [`docs/EXTERNAL-MAIL.md`](docs/EXTERNAL-MAIL.md) | connecting Mail.ru, Yandex, and other legacy mailboxes: collection, archive migration, and send-as |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | participating in development: code, tests, documentation, translations, and PR expectations |
| [`docs/INSTALL.en.md`](docs/INSTALL.en.md) | installation and operations; Russian: [`INSTALL.md`](docs/INSTALL.md) |
| [`docs/api/*.yaml`](docs/api/) | OpenAPI: auth, mail, chat, admin |
| [`ADOPTERS.md`](ADOPTERS.md) | who uses Corvex and how to add an organization |
| [`SUPPORT.md`](SUPPORT.md) | bugs, security reports, donations, and commercial help |

### Author and contact

Corvex is authored by **Artem Dobrokvashin**: [Telegram](https://t.me/Artem_Dobrokvashin). Project questions, deployments, migration planning, administrator training, and custom integrations can be discussed directly with the author. Voluntary donations are also arranged only through direct contact; payment details are not published in the repository.

Corvex is free and open under Apache-2.0, and the self-hosted core will remain so. The product requires no subscription, per-user fee, or “activation.”

### License

Corvex is licensed under the **Apache License 2.0** — see [`LICENSE`](LICENSE) for the full text and [`NOTICE`](NOTICE) for attribution and third-party components. In practice that means free self-hosted deployment with no subscription or per-user fee, plus the right to modify, distribute, and use it commercially under the Apache-2.0 terms. Contributions are accepted under the same license (see [`CONTRIBUTING.md`](CONTRIBUTING.md)).
