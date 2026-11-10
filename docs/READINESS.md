# Corvex: фактическая готовность и ворота релиза

> **Срез дополнен результатами live-приёмки чата и файлов на стенде после PR #133.** Актуальные dependency-версии —
> в `RELEASE-DEPENDENCIES.md`; перед проверкой фиксируйте точный SHA. Полный PC-pass
> G1/G2 получен на **`8cccaee`** с Boot 3.5.16. G2 выполнен на существующих volumes;
> clean-volume install не выполнялся. POP3-исправление и его клиентская приёмка
> относятся к **`d75fb97`**.

**Коротко: G0 повторно пройден; G1/G2 имеют полный PC-pass на `8cccaee`, но повторяются
для каждого release SHA; G3 выполняется и уже содержит ограниченные SMTP/IMAP-smoke и
полный согласованный POP3 995 smoke и тематическую приёмку чата/файлов; G4/G5 впереди. Corvex уже не является только
набором незапущенного кода, но ещё не production-ready.**

## 1. Что уже подтверждено фактическими проверками

| Область | Подтверждённый результат | Что этот результат не доказывает |
|---|---|---|
| Frontend, прежний срез | `npm ci`, **9 test-файлов / 33 теста**, TypeScript + modern/legacy production build успешны; production `npm audit` — **0** | Это не заменяет реальный Windows 7 + Chrome 109 и полную ручную responsive-приёмку |
| Java / Maven | **`8cccaee`:** на ПК с JDK 17 корневой `mvn -B clean verify` завершился `BUILD SUCCESS`: 12 сервисов + parent, тесты и JaCoCo. На `d75fb97` дополнительно `mail-pop3-service`: 44/44 теста | Полный reactor повторяется для каждого release SHA; targeted POP3-тест не заменяет полный прогон |
| Docker-образы | **`8cccaee`:** все 13 build-контекстов собраны. На `d75fb97` отдельно пересобран и запущен исправленный POP3-образ | Docker build не равен `mvn verify` (`-DskipTests`); чистая установка и полный rebuild следующего release SHA отдельно не доказаны |
| Запуск Compose | **`8cccaee`:** Compose config, `.env`/ключи, 20/20 основных контейнеров на существующих volumes; healthchecked — `healthy`, `nginx`/`frontend`/`spamd` без healthcheck — `Up` | Clean-volume first install, нагрузка, 72 часа и backup/restore не проверены |
| Flyway и хранилища | Чистые миграции auth/admin/mail/chat/notification исторически проверены на `b37db56`; на `8cccaee` подтверждён запуск на существующих volumes, RabbitMQ/Redis/Elasticsearch работали | Миграции между будущими релизами, clean-volume install и восстановление после потери диска не доказаны |
| Web runtime | Вход, админка и webmail запускались с реальным backend; live-проверки чата и файлов описаны ниже | Это не полная frontend-матрица и не нагрузочная приёмка |
| External Mail Connector | На тестовых ящиках подтверждён ongoing IMAP-сбор Mail.ru и Яндекса; повторные запуски не дали дублей; новое письмо Mail.ru дошло через polling. На `8cccaee` повторный regression MANUAL/SCHEDULED завершился SUCCESS без ошибок | Не приняты архивная миграция, Gmail/Outlook/custom live-сценарии и почти realtime-IDLE |
| Собственный SMTP | На текущем стенде Thunderbird подтвердил submission 587 STARTTLS, локальную доставку и сохранение Sent; письма видны получателю в webmail | Inbound 25, вся spam/AV/EICAR-матрица, outbound/retry/DSN, поиск и production-доставляемость не закрыты |
| Собственный IMAP | На `8cccaee` Thunderbird подтвердил IMAP 993 LOGIN, папки и чтение | Полный 143 STARTTLS без exception, вложения/FETCH, MOVE/EXPUNGE, поиск и restart persistence ещё в работе; Outlook отложен |
| Собственный POP3 | На `d75fb97` Thunderbird 995 без per-server exception: LOGIN/STAT/LIST/UIDL/RETR, копия на сервере, повтор без дублей, тот же UIDL после restart. Модуль: 44/44 теста | TOP, DELE-матрица, app-password/2FA, большой INBOX и Outlook не проверены |

Frontend-результат 9/33 и build/audit повторно получены 28.10.2026. Полный
Maven/Docker-pass на Boot 3.5.16 получен на ПК для `8cccaee`; более поздний
`d75fb97` покрыт targeted POP3-тестами, Docker build и реальным Thunderbird-smoke,
а не новым полным reactor. Тематические live-проверки не заменяют release-wide G1/G2.
Для PR #133 отдельно пройдены frontend 27 файлов / 102 теста, build и audit 0,
Python 89/89 и полный Maven reactor на ПК; UI-сброс пароля пока не проверен live.
Это не GitHub Actions checks: настроенных checks у репозитория на момент среза нет.

## 2. Статус по компонентам

> Полный PC-pass G1/G2 относится к `8cccaee`; clean-volume G2 не выполнялся.
> Тематические live-результаты протоколов, чата и External Mail перечислены отдельно
> и не переносятся автоматически на следующий release SHA.

| Компонент | Статус | Фактическая граница готовности |
|---|---|---|
| `auth-service`, `admin-service` | 🟡 Частично принято | Локальный вход и админка работали на стенде; сервисы прошли Maven/Docker/Flyway. Полные сценарии 2FA, app passwords, RBAC-матрицы, instant JWT revocation и AD/LDAP остаются в G3–G5 |
| `web-gateway`, frontend | 🟡 Частично принято | JWT/rate-limit и современный RU/EN UI подключены к API; frontend-тесты и оба bundle зелёные. Нужны реальный Chrome 109, оставшаяся адаптивность и ручная клиентская приёмка |
| `mail-smtp-service` | 🟡 Ограниченный клиентский smoke пройден | Submission 587 STARTTLS, локальная доставка и Sent подтверждены в Thunderbird. Inbound 25, вся spam/AV/EICAR-матрица, outbound/retry/DSN и production-доставляемость не приняты; по умолчанию SMTP выключен |
| `mail-storage-service` | 🟡 Частично принято | Maildir/CAS, webmail, очереди и поиск стартовали; локальная SMTP-доставка и POP3 raw retrieval подтверждены. Полный SMTP→spam/AV→storage→search и archive migration ещё требуют приёмки |
| `mail-spam-service`, `mail-antivirus-service` | 🟡 Инфраструктурно проверено | SpamAssassin/ClamAV и сервисы поднялись; EICAR, ложные срабатывания и поведение при недоступности сканеров нужно проверять сквозными тестами |
| `mail-imap-service` | 🟡 Ограниченный G3-smoke | IMAP 993 LOGIN/папки/чтение подтверждены. Полный 143 STARTTLS без exception, вложения/FETCH, MOVE/EXPUNGE, поиск, restart и persistent LSUB ещё не закрыты; Outlook отложен |
| `mail-pop3-service` | 🟢 Согласованный Thunderbird 995 smoke на `d75fb97` | TLS без per-server exception, LOGIN/STAT/LIST/UIDL/RETR, keep-on-server, повтор без дублей и UIDL после restart подтверждены. Остальная матрица — в README сервиса; по умолчанию сервис выключен |
| External Mail Connector | 🟡 Ограниченный live-smoke пройден | Подтверждены ongoing Mail.ru/Яндекс, app passwords, polling и dedupe. Архив 6550 писем специально не запускался; OAuth провайдеров и distributed IMAP IDLE — будущая работа |
| `chat-service` | 🟡 Тематический live-smoke пройден | Direct/group, realtime, read/unread, запрет чужой истории, идемпотентность повторного запроса и reconnect после Offline проверены; нагрузка и полная матрица функций остаются |
| `chat-file-service` | 🟡 Тематический live-smoke пройден | Upload/download участниками, запрет постороннему и EICAR проверены; большие файлы и остальные граничные случаи остаются |
| `notification-service` | 🟡 G1/G2 | Сервис и миграции прошли запуск; реальная цепочка Alertmanager→email→администратор ещё требует smoke |
| Наблюдаемость и эксплуатация | 🟡 Базовый стек поднят | Prometheus/Grafana/Loki описаны; доставка логов, alert rules, 72 часа, backup/restore и DR не приняты |

## 3. Что остаётся в G3

G3 нельзя закрывать только потому, что контейнеры зелёные. Оставшиеся темы выполняются
и принимаются отдельно:

1. **Собственная почта:** SMTP inbound/outbound, spam/AV/EICAR и поиск; IMAP 143
   STARTTLS без exception, вложения/FETCH, MOVE/EXPUNGE, папки/флаги/UID/reconnect и
   restart safety. POP3 995 согласованный smoke пройден; его расширенная матрица остаётся.
   Outlook отложен и не считается проверенным.
2. **Webmail:** чужой доступ, вложения, HTML/изображения, длинные/кириллические имена,
   Sent/DSN и сценарии из issue #99. Во время protocol smoke 02.11.2026 одна попытка
   отправки вернула HTTP 401; причина ещё не локализована, повторная webmail-приёмка
   выполняется отдельно.
3. **Identity и RBAC:** смена пароля, 2FA, app passwords, union-прав групп, защита
   последнего SUPER_ADMIN и запрет доступа к чужой почте. AD/LDAP на текущем стенде
   намеренно не настраивается.
4. **Чат:** тематический live-smoke direct/group, read/unread, файлов, security,
   идемпотентности и reconnect пройден (см. ниже). Остались полная матрица функций,
   большие файлы, отдельные граничные сценарии refresh и нагрузочная проверка.
5. **External Mail:** archive migration с контрольными папками/датами/флагами/вложениями,
   pause/resume/delete, send-as и Sent, остальные провайдеры; затем безопасно настраиваемый
   polling и устойчивый IMAP IDLE с reconnect/polling fallback.
6. **Frontend:** реальный Windows 7 + Chrome 109, смартфон/планшет, RU/EN, keyboard/Escape,
   длинный Unicode-текст и отложенная ручная матрица. Legacy build сам по себе недостаточен.
7. **Эксплуатация:** мониторинг/alerting, persistent volumes после restart, backup на
   другом диске, реальное восстановление и наблюдаемость в течение 72 часов.

### Webmail: проверка вложений и ограничения

При живой проверке полученное письмо отображалось, но кнопка скачивания не имела
обработчика; безусловная надпись о безопасности не подтверждала AV-проверку. Исправление
добавляет выдачу вложений владельцу по ID письма и индексу части. Если чистый результат
проверки письма не подтверждён, выбранное вложение проверяется ClamAV перед каждой
выдачей. При угрозе или недоступности антивируса файл не выдаётся. Статус входящего
письма относится к проверке всего письма и не является гарантией безопасности файла.

Миграция V5 намеренно не считает исторический CLEAN доказательством: прежде Sent и IMAP
APPEND могли записать CLEAN без сканирования. Старые и исходящие вложения поэтому будут
проверяться при скачивании; этот результат не присваивается всему письму. Перед
развёртыванием выполнить backup. На стенде после проверки Maven модуля и создания backup
сервис развёрнут: старое вложение повторно проверено и скачано с совпадением содержимого;
новое входящее письмо показало подтверждённый CLEAN и тоже скачано с совпадением данных.
Прямой запрос к новому вложению от постороннего вернул 404; владелец скачал его через UI.
Это ограниченный live-smoke: mail EICAR, недоступность ClamAV при скачивании и восстановление
из созданного backup отдельно не проверены. Полный release gate остаётся открытым.

### Результаты live-приёмки чата и файлов

На текущем локальном стенде проверены администратор, Анна и Иван в разных браузерах:

- Direct/group и realtime, metadata и read/unread; чужой direct не найден Иваном в UI.
- Иван получил HTTP 403 при запросе истории чужого direct, загрузке файла в него и
  скачивании файла участников; содержимое не раскрыто.
- Администратор загрузил файл и отправил сообщение (оба HTTP 201); Анна скачала файл,
  содержимое совпало. Повтор исходного запроса с тем же `clientMessageId` вернул тот же ID.
- EICAR upload отклонён HTTP 422. После перезагрузки компьютера сообщение и файл
  сохранились, файл вновь скачан; все три сеанса открылись.
- После Offline в Opera и возврата сети видны неудачные попытки подключения и новый
  WebSocket HTTP 101; новое сообщение доставлено Анне без обновления страницы.

Это тематический smoke на существующих данных, не полная security-матрица, не нагрузочный
тест и не подтверждение чистой установки или восстановления из backup.

## 4. Ворота G0–G5

| Gate | Статус | Критерий / следующий шаг |
|---|---|---|
| **G0 — frontend и статика** | ✅ Пройден; повторяется для каждого релизного SHA | Актуальный повтор: 9/33, modern+legacy build, production audit 0 |
| **G1 — Maven** | ✅ PC-pass на `8cccaee`; 🔁 повторять для release SHA | 12 сервисов + parent, `BUILD SUCCESS`, JaCoCo не понижен; на `d75fb97` дополнительно POP3 44/44, но это targeted-прогон |
| **G2 — Docker и первый запуск** | ✅ PC-pass на `8cccaee` для существующих volumes; ⏳ clean-volume | 13 образов, Compose config и 20/20 базовых контейнеров; чистая установка и targeted recreate следующих SHA не смешивать с полным повтором |
| **G3 — функциональная приёмка** | 🚧 В работе | External Mail ongoing, тематический chat/file smoke, ограниченные SMTP/IMAP и согласованный POP3 995 smoke проверены; закрыть раздел 3 целиком |
| **G4 — поддерживаемая платформа** | ⏳ Не начат | Отдельная миграция Spring Boot/Cloud/Jackson, James/JDK и observability-стека; затем повтор G1–G3 |
| **G5 — пилот и production** | ⏳ Не начат | Нагрузка, backup/restore, security hardening, AD/LDAP при необходимости, ограниченный пилот и план отката |

Подробные версии и dependency-политика: [`RELEASE-DEPENDENCIES.md`](RELEASE-DEPENDENCIES.md).
Пошаговые команды воспроизведения: [`TESTING-ON-YOUR-PC.md`](TESTING-ON-YOUR-PC.md).

## 5. Что пока запрещено

- Не направлять production MX на Corvex и не переносить рабочие ящики без плана отката.
- Не открывать публичный SMTP и не обещать сотрудникам production-ready Outlook/Thunderbird.
- Не считать подключение внешнего тестового ящика доказательством безопасности рабочей почты.
- Не включать `CORVEX_SMTP_ENABLED`, `CORVEX_IMAP_ENABLED` или `CORVEX_POP3_ENABLED`
  вне изолированного стенда до соответствующей клиентской приёмки.
- Не публиковать `.env`, app-password, internal token, сертификаты или настоящие письма
  в issue и скриншотах.

**Итог:** репозиторий содержит работающий и частично проверенный стенд, а не только UI.
До production остаются clean-volume G2, повтор G1/G2 на release SHA, полный G3,
отдельный G4 и контролируемый пилот G5.
