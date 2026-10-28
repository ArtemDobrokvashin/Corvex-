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
[![G0 passed on main](https://img.shields.io/badge/G0-current%20main%20passed-2ea44f)](docs/RELEASE-DEPENDENCIES.md)
[![G1-G2 accepted baseline](https://img.shields.io/badge/G1--G2-b37db56%20PC%20pass%3B%20main%20rerun%20needed-d29922)](docs/READINESS.md)
[![G3 in progress](https://img.shields.io/badge/G3-functional%20acceptance%20in%20progress-d29922)](docs/READINESS.md)
![RU EN](https://img.shields.io/badge/interface-RU%20%2F%20EN-3157d5)
![Chrome 109](https://img.shields.io/badge/browser-Chrome_109%2B-3157d5)
[![Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-2ea44f)](LICENSE)
![Free and open source](https://img.shields.io/badge/free-%26_open_source-2ea44f)
[![Telegram](https://img.shields.io/badge/Telegram-Artem__Dobrokvashin-26A5E4?logo=telegram&logoColor=white)](https://t.me/Artem_Dobrokvashin)

[![Corvex Mail](docs/screenshots/mail-ru.png)](docs/screenshots/mail-ru.png)

<sub>Real screenshot of the built frontend with clearly labelled demo data. Screenshots prove UI rendering only; verified runtime results are listed separately in READINESS.md.</sub>

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
> **Corvex пока НЕ production-ready.** Полные **G1/G2 были пройдены на ПК только для baseline `b37db56`** (Boot 3.2.12 / Cloud 2023.0.6 / James 3.8.2): 12 сервисов + parent в Maven, 13 Docker-образов, Flyway и базовый стек. В текущем `main` уже Boot 3.5.16, поэтому **G1/G2 на актуальном SHA нужно повторить**; старый результат задним числом не переносится. **G3 идёт:** limited ongoing Mail.ru/Яндекса и базовый realtime-чат проверены живьём, но собственные SMTP/IMAP/POP3, полный двухпользовательский чат, archive migration и backup/restore ещё не приняты. Это **pre-1.0 стенд, а не замена рабочей почте**. Не направляйте production MX, не переносите рабочие ящики и не включайте публичный SMTP до оставшихся ворот в [`docs/READINESS.md`](docs/READINESS.md).

### Попробовать за 2 минуты

Нужен только актуальный Node.js: 22.22.2+ LTS, 24.15+ или 26+ (см. `engines` в `frontend/package.json`):

```bash
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git
cd Corvex-/frontend
npm ci
npm run dev
```

Откройте `http://localhost:5173` и нажмите **«Посмотреть демо без сервера»**. Почта и чат покажут учебные данные с жёлтой плашкой. В полном стеке вход, админка, webmail и базовый чат уже запускались с реальным backend. Собственные почтовые протоколы Stage 3 и полная двухпользовательская приёмка чата остаются в G3; демо-режим не выдаётся за серверный результат.

### Об авторе и участии в проекте

Corvex придумал и сейчас ведёт один разработчик-энтузиаст — **Artem Dobrokvashin**. Это личный open-source проект, а не продукт большой команды: код, архитектура и документация развиваются открыто и в обычном для такого проекта темпе.

Если идея собственной корпоративной почты вам близка и есть желание присоединиться, милости просим. Форматы участия обычные: код, тесты, проверка на своём стенде или почтовом клиенте, документация и переводы. С чего начать, описано в [`CONTRIBUTING.md`](CONTRIBUTING.md); маршрут полной первой проверки — в [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md). Для сообщения об ошибке достаточно SHA коммита, версий инструментов, выполненных команд и первой ошибки. Не прикладывайте `.env`, пароли, ключи и настоящие письма, а тесты проводите на отдельной VM и тестовом домене.

Скрытой телеметрии в Corvex нет. При желании информацию о своём стенде или внедрении можно добавить в [`ADOPTERS.md`](ADOPTERS.md).

### Скриншоты

Все кадры ниже — реальные скриншоты собранного фронтенда в Chromium с явно обозначенными демонстрационными данными. Скриншот доказывает только отрисовку UI; фактические Maven/Docker/runtime-результаты перечислены отдельно в [`docs/READINESS.md`](docs/READINESS.md).

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
- **Почта Stage 2:** durable SMTP spool, SpamAssassin → ClamAV → Maildir/CAS, серверный webmail, исходящая MX-очередь с retry/DSN, DKIM и submission 587 с AUTH+STARTTLS. Сервисы прошли Maven и Docker-запуск на PC baseline `b37db56`; для текущего `main` после смены Boot нужен повтор, а собственный сквозной SMTP-сценарий ещё не принят в G3.
- **External Mail Connector:** старые ящики Mail.ru, Яндекса, Gmail, Outlook или custom-сервера подключаются к Corvex по IMAP/POP3: пресеты, app-password с AES-256-GCM, сбор новой почты, импорт архива, дедупликация и send-as через SMTP провайдера. На ПК подтверждён ограниченный ongoing-сбор Mail.ru/Яндекса без дублей; новое письмо Mail.ru появилось после polling с ожидаемой задержкой 1–2 минуты. Архивная миграция и остальные провайдеры ещё требуют live-приёмки. Инструкция: [`docs/EXTERNAL-MAIL.md`](docs/EXTERNAL-MAIL.md).
- **Почтовые протоколы Stage 3:** внутренний протокольный API хранилища (папки/UID/MODSEQ/флаги/APPEND/COPY/QUOTA), POP3 (INBOX, стабильные UIDL, implicit TLS 995) и IMAP-движок Apache James 3.8.2 (143 STARTTLS/993, MOVE/QUOTA/CONDSTORE) собирались и стартовали в Docker на PC baseline `b37db56`; текущий `main` требует повторного G1/G2, а протоколы выключены по умолчанию до клиентского G3 в Thunderbird/Outlook. Подписки LSUB пока живут в памяти процесса IMAP.
- **Чат Stage 4:** PostgreSQL-backed комнаты и сообщения, STOMP/RabbitMQ realtime, проверка членства, реакции, закрепления, прочтения, typing/presence, поиск и файлы с ClamAV. На ПК подтверждены история, SockJS handshake, отправка сообщений и emoji; полный двухпользовательский security/file/reconnect-smoke остаётся в G3.
- **Современный frontend без отказа от старого парка:** React 19, Vite 8 и TypeScript 7 собираются в modern-бандл для актуальных браузеров и legacy-бандл для Chrome 109; Markdown проходит через единый DOMPurify-контур с тестами.

#### Следующие этапы и открытые ворота

- **G3 — оставшаяся функциональная приёмка:** собственная сквозная почта, Thunderbird/Outlook, двухпользовательский чат и файлы, webmail/вложения, archive migration и backup/restore.
- **G4 — поддерживаемая платформа:** согласованные Spring Boot/Cloud/Jackson, Apache James/JDK и замена EOL Promtail на Grafana Alloy отдельным апгрейдом с повтором G1–G3.
- **Stage 5 и enterprise-hardening:** AD/LDAP, mTLS между сервисами, мгновенный отзыв JWT, строгая CSP, transactional outbox, персистентные LSUB, распределённый IMAP IDLE, keyword-флаги/ACL, CI и нагрузочные профили до 1000 сотрудников.

### Статус и roadmap

| Этап | Содержимое | Статус |
|---|---|---|
| 1 | Identity, пользователи, группы, RBAC, аудит, админка | 🟡 локальный вход/админка запускались; G1/G2 — только `b37db56`, на `main` повторить; полная identity/RBAC-приёмка остаётся в G3 |
| 2 | Durable SMTP-доставка, storage, spam/AV, поиск, исходящая очередь | 🟡 сервисы собраны/запущены на `b37db56`, на `main` повторить; собственный сквозной SMTP ещё не принят |
| 3 | IMAP/POP3 на Apache James, Outlook/Thunderbird | 🟡 G1/G2 пройдены только на `b37db56`, на `main` повторить; клиентский smoke не выполнен, протоколы выключены по умолчанию |
| 4 | Persistent realtime-чат, файлы, presence | 🟡 базовый realtime подтверждён; двухпользовательский chat/file/security-smoke впереди |
| G0 | Frontend и статические ворота | ✅ текущий `main`: 9 файлов / 33 теста, modern+legacy build, production audit 0 |
| G1 | Maven по всем сервисам | ✅ только baseline `b37db56`: 12 сервисов + parent, `BUILD SUCCESS`; 🔁 на текущем `main` с Boot 3.5.16 повторить |
| G2 | Docker, Flyway и первый запуск | ✅ только baseline `b37db56`: 13 образов, 20/20 основных контейнеров; healthchecked — `healthy`, `nginx`/`frontend`/`spamd` — `Up`; 🔁 на текущем `main` повторить |
| G3 | Функциональная приёмка | 🚧 External Mail ongoing и базовый чат проверены; остальное перечислено в [`docs/READINESS.md`](docs/READINESS.md) |
| G4–G5 | Поддерживаемая платформа, пилот и production | ⏳ не начаты; подробности в [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) |
| 5 | AD/LDAP, mTLS, production hardening | 📐 спроектировано; выполняется после соответствующих ворот |

SMTP/IMAP/POP3 остаются выключенными по умолчанию до соответствующей сквозной G3-приёмки. Успешные Maven/Docker сами по себе не включают протоколы и не доказывают доставку письма.

Текущий исходный baseline — Java 17 и Spring Boot 3.5.16; gateway использует Spring Cloud 2025.0.3, остальные POM пока содержат BOM 2023.0.6, IMAP/POP3 — James 3.8.2. PR #95 уже содержал Boot 3.5.16 при merge, тогда как полный PC-pass был получен раньше на `b37db56` с Boot 3.2.12 / Cloud 2023.0.6. Поэтому текущий `main` не считается автоматически принятым по G1/G2. Согласованная поддерживаемая платформа остаётся отдельным G4. Dependabot ограничен сгруппированными minor/patch-обновлениями и одним открытым PR на ecosystem.

### Production-ready? Пока нет

**Прямой ответ: нет. Corvex сегодня нельзя честно продавать или внедрять как production-ready продукт.** Но это уже не только написанный, но незапущенный код: Maven/Docker/Flyway действительно проверены на ПК для `b37db56`, а часть живых сценариев — в более поздних тематических проверках. Для текущего `main` нужно повторить G1/G2 после смены Boot; до доверия рабочей переписке также остаются полный G3, G4 и контролируемый пилот G5.

Сейчас Corvex подходит для:

- изучения интерфейса и архитектуры;
- разработки и функциональной приёмки;
- изолированного тестового стенда с тестовыми ящиками и без production MX;
- подготовки DNS/сертификатов на отдельном тестовом домене.

Сейчас Corvex **не подходит** для:

- замены рабочего корпоративного почтового сервера;
- переноса рабочих почтовых ящиков и переписки;
- публичного включения портов 25/587 как боевого сервиса;
- обещаний production-ready Outlook/Thunderbird или полностью проверенного корпоративного чата: базовый чат уже работал на стенде, но клиентские и двухпользовательские сценарии ещё не закрыты.

#### Что уже подтверждено, а что нет

| Область | Подтверждено фактически | Не подтверждено / остаётся |
|---|---|---|
| Frontend | Текущий `main`: 9 файлов / 33 теста; TypeScript, modern+legacy build; production `npm audit` — 0 | реальный Windows 7 + Chrome 109, полная responsive/E2E-матрица |
| Java | На PC baseline `b37db56`: 12 сервисов + parent, тесты и JaCoCo, `BUILD SUCCESS` | текущий `main` с Boot 3.5.16: полный повтор; затем сквозные сценарии поверх unit/context-тестов |
| Docker/инфраструктура | На PC baseline `b37db56`: 13 образов, Compose config, Flyway, 20/20 основных контейнеров; healthchecked healthy | текущий `main`: повторный запуск; затем 72 часа, нагрузка, monitoring-профиль, restore и откат |
| Внешняя почта | Ongoing IMAP Mail.ru/Яндекс на тестовых ящиках; повторы без дублей; новое письмо доставлено polling-ом | архив, остальные провайдеры, весь send-as/Sent smoke, distributed IDLE |
| Чат | История, SockJS handshake, сообщения и emoji на реальном backend | два пользователя, direct/group security, файлы/EICAR, refresh/reconnect и restart |
| Собственная почта | Сервисы SMTP/IMAP/POP3 собраны и запущены; webmail подключён к backend | SMTP→spam→AV→Maildir→webmail, внешняя доставка/DKIM и Thunderbird/Outlook IMAP/POP3 |
| Эксплуатация | Мониторинг и backup-скрипты есть; базовая инфраструктура стартует | восстановление из бэкапа, откат релиза, 1000 сотрудников, security-тесты и 72-часовой soak |

> Успешная сборка Docker-образа сама по себе не равна пройденным Java-тестам: Dockerfile собирают сервисы с `-DskipTests`. Поэтому `mvn verify` нужно запускать отдельно до или независимо от Compose.

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

Отметки ниже показывают результат на проверенном baseline, а не вечную гарантию для любого
будущего коммита. G0–G2 повторяются на каждом релизном SHA.

- [ ] **0. Не открывать прод раньше времени.** Держать `CORVEX_SMTP_ENABLED=false`, не менять рабочие MX и работать на отдельной VM/тестовом поддомене.
- [ ] **1. Повторить зелёный Maven на текущем `main`.** Исторический PC-pass уже есть для `b37db56` (Boot 3.2.12): 12 сервисов + parent, тесты и JaCoCo. После перехода `main` на Boot 3.5.16 этот результат недостаточен; из корня выполнить:

  ```powershell
  mvn -B clean verify
  ```

  Пункты 1–3 одной командой также выполняет `scripts\verify.ps1` (Windows Server) или `scripts/verify.sh` (Linux); с флагом `-DockerBuild` / `--docker-build` дополнительно собираются все Docker-образы.

- [x] **2. Зелёный frontend текущего `main`:** `npm ci`, **9/33** test files/tests, `npm run build` с modern+legacy и production audit 0. Каждый релизный кандидат повторяет G0 без сокращений.
- [ ] **3. Повторить базовый Compose-подъём на текущем `main`.** Для `b37db56` на ПК проверены config, 13 образов, Flyway и 20/20 основных контейнеров. После смены Boot повторить чистый запуск и проверить restart loops/DLQ; последующие точечные gateway/chat-фиксы не заменяют полный повтор.
- [ ] **4. Полный identity/admin smoke.** Bootstrap-админ, смена пароля, второй пользователь, группы/права, lockout, TOTP, app-password, аудит и запрет действий над чужими данными.
- [ ] **5. Собственный почтовый smoke на тестовом домене.** Внешнее письмо → INBOX; локальное webmail-письмо; внешнее письмо на контролируемый MX; вложение; Bcc не виден другим получателям; письмо переживает рестарт контейнеров.
- [ ] **6. Негативные почтовые сценарии.** Несуществующий локальный получатель — `550`; open relay запрещён; storage/Rabbit недоступны — `451`, а не ложный `250 OK`; временный `4xx` уходит в retry; постоянный `5xx` создаёт DSN; повтор события не создаёт дубль.
- [ ] **7. Submission и доставляемость.** STARTTLS обязателен, app-password работает, чужой `MAIL FROM` отклоняется `553`, DKIM/SPF/DMARC проходят у внешнего получателя, PTR совпадает с SMTP hostname, mail-tester ≥ 9/10.
- [ ] **8. Полный чат Stage 4.** Два пользователя: direct/группа, история после рестарта, запрет чужой комнаты/файла, идемпотентный `clientMessageId`, реакции/pin/read, EICAR и STOMP после refresh JWT. Базовые сообщения уже проверены, но этот пункт пока не закрыт целиком.
- [ ] **8A. Клиентский smoke Stage 3 (IMAP/POP3).** Только на стенде включить протоколы с PKCS12/keystore и проверить Thunderbird/Outlook: LOGIN, LIST/SELECT/FETCH, вложения, Sent, MOVE/EXPUNGE, поиск, POP3 UIDL и DELE/QUIT. Подробная команда и критерии — в [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md).
- [ ] **8B. Оставшийся External Mail smoke.** Archive migration, send-as/Sent, pause/resume/delete, права владельца и остальные провайдеры; затем настраиваемый polling и устойчивый IMAP IDLE с reconnect/fallback.
- [ ] **9. Эксплуатационная приёмка.** Backup → restore на чистой VM, откат образов, алерт при остановке, ротация сертификата, нагрузка из [`docs/10-test-strategy.md`](docs/10-test-strategy.md), security-проход, мониторинг/alerting и реальная Windows 7 + Chrome 109.
- [ ] **9A. Поддерживаемая dependency-платформа (G4).** Отдельно выбрать и проверить поддерживаемые Spring/James/JDK, заменить Promtail на Alloy и повторить Maven, Docker, почтовые и чат-smoke без сокращений.
- [ ] **10. Пилот, а не сразу «прод для всех».** Минимум 72 часа на тестовом/пилотном домене с мониторингом очередей, DLQ, диска, bounce-rate и жалоб пользователей; только после этого переносить ограниченную группу ящиков.

Даже после закрытия перечисленных пунктов первый честный статус — **pilot-ready для ограниченного контура**, а не автоматически production-ready всей платформы. Полный G3, G4 и Stage 5 либо осознанное сужение заявленного объёма остаются обязательными.

Если сборка упала, для issue приложите SHA коммита, первую ошибку Maven или полный стек Spring, `docker compose ps` и хвост логов соответствующего сервиса. **Не прикладывайте** `.env`, пароли, internal token, приватные ключи и настоящие письма.

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
docker compose ps                            # healthchecked-сервисы healthy; nginx/frontend/spamd — Up
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
> **Corvex is NOT production-ready.** Full **G1/G2 passed on a PC only for baseline `b37db56`** (Boot 3.2.12 / Cloud 2023.0.6 / James 3.8.2): 12 services plus the parent in Maven, 13 Docker images, Flyway, and the base stack. Current `main` already uses Boot 3.5.16, so **G1/G2 must be repeated on the current SHA**; the old result is not retroactively transferred. **G3 is in progress:** limited Mail.ru/Yandex ongoing collection and basic realtime chat ran live, but own SMTP/IMAP/POP3, full two-user chat, archive migration, and backup/restore are not accepted. This is a **pre-1.0 bench—not a replacement for working mail**. Do not point production MX records at Corvex, migrate working mailboxes, or expose public SMTP until the remaining gates in [`docs/READINESS.md`](docs/READINESS.md) pass.

### Try it in 2 minutes

Only a current Node.js patch line is required: 22.22.2+ LTS, 24.15+, or 26+ (see `engines` in `frontend/package.json`):

```bash
git clone -b main https://github.com/ArtemDobrokvashin/Corvex-.git
cd Corvex-/frontend
npm ci
npm run dev
```

Open `http://localhost:5173` and click **“View the demo without a server.”** Mail and Messages show clearly labelled sample data. In the full stack, sign-in, administration, webmail, and basic chat have already run against the real backend. Own Stage 3 mail protocols and complete two-user chat acceptance remain in G3; demo mode is not presented as server evidence.

### About the author and joining the project

Corvex was created and is currently developed by one enthusiast, **Artem Dobrokvashin**. It is a personal open-source project rather than the product of a large team: the code, architecture, and documentation evolve in the open at a sustainable individual pace.

If the idea of running your own corporate mail service appeals to you and you would like to take part, you are welcome to do so. The usual forms of participation apply: code, tests, checks on your own bench or mail client, documentation, and translations. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md); the full first-test route is described in [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md) (Russian). A useful bug report contains the commit SHA, tool versions, commands, and the first error. Never attach `.env`, passwords, keys, or real mail, and use a separate VM and test domain.

Corvex contains no hidden telemetry. If you wish, you can record a bench or deployment in [`ADOPTERS.md`](ADOPTERS.md).

### Screenshots

Every frame below is a real screenshot of the built frontend in Chromium with clearly labelled demo data. A screenshot proves UI rendering only; actual Maven/Docker/runtime results are listed separately in [`docs/READINESS.md`](docs/READINESS.md).

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
- **Stage 2 mail:** durable SMTP spool, SpamAssassin → ClamAV → Maildir/CAS, server-backed webmail, outbound MX queue with retry/DSN, DKIM, and submission on 587 with AUTH+STARTTLS. The services passed Maven and Docker startup on PC baseline `b37db56`; current `main` needs a repeat after the Boot change, and the own end-to-end SMTP scenario is not yet accepted in G3.
- **External Mail Connector:** connect existing Mail.ru, Yandex, Gmail, Outlook, or custom IMAP/POP3 mailboxes with presets, AES-256-GCM app-password storage, new-mail collection, archive import, dedupe, and send-as through provider SMTP. Limited ongoing Mail.ru/Yandex collection has passed on a PC without duplicates; a new Mail.ru message arrived through polling with the expected 1–2 minute delay. Archive migration and the other providers still need live acceptance. Guide: [`docs/EXTERNAL-MAIL.md`](docs/EXTERNAL-MAIL.md).
- **Stage 3 mail protocols:** internal storage protocol API (folders/UID/MODSEQ/flags/APPEND/COPY/QUOTA), POP3 (INBOX, stable UIDL, implicit TLS 995), and Apache James 3.8.2 IMAP (143 STARTTLS/993, MOVE/QUOTA/CONDSTORE) built and started in Docker on PC baseline `b37db56`; current `main` needs a G1/G2 repeat, and the protocols remain disabled by default until Thunderbird/Outlook G3 acceptance. LSUB subscriptions currently live only in IMAP process memory.
- **Stage 4 chat:** PostgreSQL-backed rooms/messages, STOMP/RabbitMQ realtime, membership checks, reactions, pins, read state, typing/presence, search, and ClamAV-scanned files. History, the SockJS handshake, message sending, and emoji have passed on a PC; the full two-user security/file/reconnect smoke remains in G3.
- **A modern frontend that does not abandon the old fleet:** React 19, Vite 8, and TypeScript 7 produce a modern bundle for current browsers and a legacy bundle for Chrome 109; chat Markdown passes through one tested DOMPurify path.

#### Next stages and open gates

- **G3 — remaining functional acceptance:** own end-to-end mail, Thunderbird/Outlook, two-user chat/files, webmail/attachments, archive migration, and backup/restore.
- **G4 — the supported platform:** aligned Spring Boot/Cloud/Jackson, Apache James/JDK, and Grafana Alloy instead of EOL Promtail, as a separate upgrade followed by G1–G3 again.
- **Stage 5 and enterprise hardening:** AD/LDAP, service-to-service mTLS, instant JWT revocation, strict CSP, a transactional outbox, persistent LSUB, distributed IMAP IDLE, keyword flags/ACL, CI, and load profiles for 1,000 employees.

### Status and roadmap

| Stage | Scope | Status |
|---|---|---|
| 1 | Identity, users, groups, RBAC, audit, administration | 🟡 local sign-in/admin ran; G1/G2 only on `b37db56`, repeat on `main`; full identity/RBAC acceptance remains in G3 |
| 2 | Durable SMTP delivery, storage, spam/AV, search, outbound queue | 🟡 services built/started on `b37db56`, repeat on `main`; own end-to-end SMTP is not accepted yet |
| 3 | Apache James IMAP/POP3, Outlook/Thunderbird | 🟡 G1/G2 passed only on `b37db56`, repeat on `main`; client smoke has not run, protocols remain disabled by default |
| 4 | Persistent realtime chat, files, presence | 🟡 basic realtime confirmed; two-user chat/file/security smoke remains |
| G0 | Frontend and static gates | ✅ current `main`: 9 files / 33 tests, modern+legacy build, production audit 0 |
| G1 | Maven for every service | ✅ baseline `b37db56` only: 12 services + parent, `BUILD SUCCESS`; 🔁 repeat on current `main` with Boot 3.5.16 |
| G2 | Docker, Flyway, and first launch | ✅ baseline `b37db56` only: 13 images, 20/20 core containers; healthchecked services `healthy`, `nginx`/`frontend`/`spamd` `Up`; 🔁 repeat on current `main` |
| G3 | Functional acceptance | 🚧 External Mail ongoing and basic chat verified; the rest is listed in [`docs/READINESS.md`](docs/READINESS.md) |
| G4–G5 | Supported platform, pilot, and production | ⏳ not started; see [`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) |
| 5 | AD/LDAP, mTLS, production hardening | 📐 designed; scheduled after the relevant gates |

SMTP/IMAP/POP3 remain disabled by default until their end-to-end G3 acceptance. Green Maven/Docker checks do not enable the protocols or prove message delivery by themselves.

The current source baseline is Java 17 and Spring Boot 3.5.16; the gateway uses Spring Cloud 2025.0.3, the other POMs still contain BOM 2023.0.6, and IMAP/POP3 use James 3.8.2. PR #95 already contained Boot 3.5.16 when merged, whereas the full PC pass was obtained earlier on `b37db56` with Boot 3.2.12 / Cloud 2023.0.6. Current `main` is therefore not automatically G1/G2-accepted. An aligned supported platform remains separate G4 work. Dependabot is limited to grouped minor/patch updates and one open PR per ecosystem.

### Production-ready? Not yet

**The direct answer is no. Corvex must not honestly be sold or deployed as a production-ready product today.** But it is no longer merely unlaunched code: Maven/Docker/Flyway actually passed on a PC for `b37db56`, and some live scenarios passed in later topical checks. Current `main` needs a G1/G2 repeat after the Boot change; trusting working correspondence also requires full G3, G4, and a controlled G5 pilot.

Corvex is currently appropriate for:

- evaluating the interface and architecture;
- development and functional acceptance;
- an isolated test bench with disposable mailboxes and no production MX records;
- preparing DNS and certificates on a separate test domain.

Corvex is currently **not** appropriate for:

- replacing a working corporate mail server;
- migrating working mailboxes or correspondence;
- exposing ports 25/587 as a production service;
- promising production-ready Outlook/Thunderbird or fully accepted corporate chat: basic chat ran on the bench, but mail-client and two-user scenarios remain open.

#### What is proven—and what is not

| Area | Actually proven | Not proven / remaining |
|---|---|---|
| Frontend | Current `main`: 9 files / 33 tests; TypeScript, modern+legacy build; production `npm audit` is 0 | real Windows 7 + Chrome 109 and the complete responsive/E2E matrix |
| Java | On PC baseline `b37db56`: 12 services + parent, tests and JaCoCo, `BUILD SUCCESS` | current `main` with Boot 3.5.16: full repeat; then end-to-end scenarios beyond unit/context tests |
| Docker/infrastructure | On PC baseline `b37db56`: 13 images, Compose config, Flyway, 20/20 core containers; healthchecked services healthy | current `main`: repeat launch; then 72 hours, load, monitoring profile, restore, and rollback |
| External mail | Ongoing IMAP for Mail.ru/Yandex test mailboxes; repeated runs without duplicates; a new message delivered by polling | archive, other providers, complete send-as/Sent smoke, distributed IDLE |
| Chat | History, SockJS handshake, messages, and emoji against the real backend | two users, direct/group security, files/EICAR, refresh/reconnect, and restart |
| Own mail | SMTP/IMAP/POP3 services built and started; webmail connected to the backend | SMTP→spam→AV→Maildir→webmail, external delivery/DKIM, and Thunderbird/Outlook IMAP/POP3 |
| Operations | Monitoring and backup scripts exist; base infrastructure starts | backup restore, release rollback, 1,000-user load, security testing, and a 72-hour soak |

> A successful Docker image build is not the same as passing Java tests: the Dockerfiles package services with `-DskipTests`. Run `mvn verify` separately before or independently of Compose.

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

The checkmarks below record results on the verified baseline, not a permanent guarantee for
future commits. Repeat G0–G2 on every release SHA.

- [ ] **0. Keep production closed.** Keep `CORVEX_SMTP_ENABLED=false`, do not change working MX records, and use a separate VM/test subdomain.
- [ ] **1. Repeat green Maven on current `main`.** The historical PC pass exists for `b37db56` (Boot 3.2.12): 12 services + parent, tests, and JaCoCo. After `main` moved to Boot 3.5.16, that result is insufficient; run from the repository root:

  ```powershell
  mvn -B clean verify
  ```

  `scripts\verify.ps1` (Windows Server) or `scripts/verify.sh` (Linux) runs gates 1–3 as one command; add `-DockerBuild` / `--docker-build` to also build every Docker image.

- [x] **2. Make the current `main` frontend green:** `npm ci`, **9 files / 33 tests**, `npm run build` with modern+legacy output, and production audit 0. Every release candidate repeats G0 without shortcuts.
- [ ] **3. Repeat the base Compose launch on current `main`.** For `b37db56`, a PC passed config validation, 13 images, Flyway, and 20/20 core containers. Repeat a clean launch after the Boot change and inspect restart loops/DLQs; later targeted gateway/chat fixes do not replace a full repeat.
- [ ] **4. Pass full identity/admin smoke.** Bootstrap admin, password change, a second user, groups/permissions, lockout, TOTP, app passwords, audit, and cross-user data isolation.
- [ ] **5. Pass own mail smoke on a test domain.** External mail reaches INBOX; local webmail works; external delivery reaches a controlled MX; attachments work; Bcc is not exposed; mail survives container restarts.
- [ ] **6. Pass negative mail cases.** Unknown local recipient returns `550`; open relay is denied; unavailable storage/Rabbit returns `451`, not a false `250 OK`; temporary `4xx` retries; permanent `5xx` creates a DSN; redelivered events do not duplicate mail.
- [ ] **7. Pass submission and deliverability.** STARTTLS is mandatory, app passwords work, a foreign `MAIL FROM` returns `553`, DKIM/SPF/DMARC pass at an external recipient, PTR matches the SMTP hostname, and mail-tester scores at least 9/10.
- [ ] **8. Pass the full Stage 4 chat smoke.** Two users in direct/group rooms; history after restart; no cross-user room/file access; idempotent `clientMessageId`; reactions/pins/read state; EICAR; and STOMP across JWT refresh. Basic messages passed, but this item is not complete.
- [ ] **8A. Pass Stage 3 mail-client smoke (IMAP/POP3).** On a bench only, enable the protocols with PKCS12/keystore configuration and verify Thunderbird/Outlook: LOGIN, LIST/SELECT/FETCH, attachments, Sent, MOVE/EXPUNGE, search, POP3 UIDL, and DELE/QUIT. See [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md) for commands and criteria.
- [ ] **8B. Finish External Mail smoke.** Archive migration, send-as/Sent, pause/resume/delete, owner permissions, and other providers; then configurable polling and resilient IMAP IDLE with reconnect/fallback.
- [ ] **9. Pass operational acceptance.** Backup → restore on a clean VM, rollback to previous images, service-down alerting, certificate rotation, the load targets in [`docs/10-test-strategy.md`](docs/10-test-strategy.md), a security pass, monitoring/alerting, and real Windows 7 + Chrome 109.
- [ ] **9A. Move to the supported dependency platform (G4).** Separately select and verify supported Spring/James/JDK versions, replace Promtail with Alloy, and repeat Maven, Docker, mail, and chat smoke tests without shortcuts.
- [ ] **10. Pilot before production.** Run at least 72 hours on a test/pilot domain while monitoring queues, DLQs, disk, bounce rate, and user reports; only then migrate a limited mailbox group.

Even after these items, the first honest label is **pilot-ready for a limited scope**, not automatically production-ready for the whole platform. Full G3, G4, and Stage 5—or a deliberate narrowing of the advertised product scope—remain mandatory.

If a build fails, attach the commit SHA, the first Maven error or complete Spring stack trace, `docker compose ps`, and the relevant service log tail to the issue. **Never attach** `.env`, passwords, the internal token, private keys, or real mail.

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
docker compose ps                            # healthchecked services healthy; nginx/frontend/spamd Up
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
