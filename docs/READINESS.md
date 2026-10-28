# Corvex: фактическая готовность и ворота релиза

> **Срез актуализирован 28 октября 2026 года.** Актуальные dependency-версии —
> в `RELEASE-DEPENDENCIES.md`; перед проверкой фиксируйте точный SHA. Полный PC-pass
> G1/G2 относится к более раннему baseline **`b37db56`** с Boot 3.2.12 / Cloud
> 2023.0.6 / James 3.8.2. Текущий `main` уже содержит Boot 3.5.16 и более новые
> dependency-пины, поэтому его G1/G2 нужно повторить.

**Коротко: G0 повторно пройден на текущем `main`; G1/G2 имеют полный PC-pass только
для `b37db56` и требуют повтора на текущем Boot 3.5.16; G3 выполняется; G4/G5 впереди.
Corvex уже не является только набором незапущенного кода, но ещё не production-ready.**

## 1. Что уже подтверждено фактическими проверками

| Область | Подтверждённый результат | Что этот результат не доказывает |
|---|---|---|
| Frontend на текущем `main` | `npm ci`, **9 test-файлов / 33 теста**, TypeScript + modern/legacy production build успешны; production `npm audit` — **0** | Это не заменяет реальный Windows 7 + Chrome 109 и полную ручную responsive-приёмку |
| Java / Maven | **Только `b37db56`:** на ПК с JDK 17 корневой `mvn -B clean verify` завершился `BUILD SUCCESS`: 12 сервисов + parent, тесты и JaCoCo | Текущий `main` с Boot 3.5.16 этим результатом не покрыт; нужен полный повтор |
| Docker-образы | **На `b37db56`:** все 13 build-контекстов собраны; позднее были отдельные пересборки gateway/chat | Полный Docker-запуск текущего `main` нужно повторить; Docker build не равен `mvn verify` (`-DskipTests`) |
| Первый запуск | **На `b37db56`:** Compose config, `.env`/ключи, 20/20 основных контейнеров; healthchecked — `healthy`, `nginx`/`frontend`/`spamd` без healthcheck — `Up` | Текущий `main`: повторный запуск; нагрузка, 72 часа и backup/restore также не проверены |
| Flyway и хранилища | **На `b37db56`:** миграции auth/admin/mail/chat/notification применились на чистом PostgreSQL; RabbitMQ, Redis и Elasticsearch стартовали | Текущий `main`: повтор; миграции между будущими релизами и восстановление после потери диска также не доказаны |
| Web runtime | Вход, админка и webmail запускались с реальным backend; базовый чат проверен: история, SockJS handshake HTTP 200, отправка сообщений и emoji | Полный двухпользовательский chat/file/security smoke и вся frontend-матрица ещё не закрыты |
| External Mail Connector | На тестовых ящиках подтверждён ongoing IMAP-сбор Mail.ru и Яндекса; повторные запуски не дали дублей; новое письмо Mail.ru дошло через polling | Не приняты архивная миграция, Gmail/Outlook/custom live-сценарии и почти realtime-IDLE |

Frontend-результат 9/33 и build/audit повторно получены на текущем `main` 28.10.2026.
Полный Maven/Docker-pass получен на ПК для `b37db56` **до merge PR #95**, который
уже содержал Boot 3.5.16. Более поздние тематические gateway/chat/External Mail
проверки не заменяют полный G1/G2 на текущем `main`. Это не GitHub Actions checks:
настроенных checks у репозитория на момент среза нет.

## 2. Статус по компонентам

> Если ниже указано «G1/G2», «Maven/Docker пройдены» или «собран и запущен» без
> отдельного SHA, это исторический результат для `b37db56`, а не полный повтор на
> текущем `main`. Тематические live-результаты чата и External Mail перечислены отдельно.

| Компонент | Статус | Фактическая граница готовности |
|---|---|---|
| `auth-service`, `admin-service` | 🟡 Частично принято | Локальный вход и админка работали на стенде; сервисы прошли Maven/Docker/Flyway. Полные сценарии 2FA, app passwords, RBAC-матрицы, instant JWT revocation и AD/LDAP остаются в G3–G5 |
| `web-gateway`, frontend | 🟡 Частично принято | JWT/rate-limit и современный RU/EN UI подключены к API; frontend-тесты и оба bundle зелёные. Нужны реальный Chrome 109, оставшаяся адаптивность и ручная клиентская приёмка |
| `mail-smtp-service` | 🟡 Собран и запущен, сквозная почта не принята | Durable spool, retry/DSN, DKIM и submission реализованы; собственный end-to-end SMTP с реальными клиентами ещё не проверен. По умолчанию SMTP остаётся выключен |
| `mail-storage-service` | 🟡 Частично принято | Maildir/CAS, webmail, очереди и поиск стартовали; внешняя почта прошла ограниченный live-smoke. Собственный SMTP→spam→AV→storage→search и archive migration ещё требуют приёмки |
| `mail-spam-service`, `mail-antivirus-service` | 🟡 Инфраструктурно проверено | SpamAssassin/ClamAV и сервисы поднялись; EICAR, ложные срабатывания и поведение при недоступности сканеров нужно проверять сквозными тестами |
| `mail-imap-service` | 🟡 G1/G2, не G3 | Apache James 3.8.2, STARTTLS/implicit TLS, UID/MODSEQ/CONDSTORE/MOVE/QUOTA собраны и стартуют. Thunderbird/Outlook, persistent LSUB и ACL/keyword flags ещё не приняты; сервис выключен по умолчанию |
| `mail-pop3-service` | 🟡 G1/G2, не G3 | James POP3, UIDL и implicit TLS собраны и стартуют. Реальный клиентский smoke ещё не выполнен; сервис выключен по умолчанию |
| External Mail Connector | 🟡 Ограниченный live-smoke пройден | Подтверждены ongoing Mail.ru/Яндекс, app passwords, polling и dedupe. Архив 6550 писем специально не запускался; OAuth провайдеров и distributed IMAP IDLE — будущая работа |
| `chat-service` | 🟡 Базовый runtime пройден | Persistent rooms/messages и SockJS работали на стенде. Два пользователя, direct/group security, refresh JWT, reconnect и перезапуск под нагрузкой ещё нужно проверить комплексно |
| `chat-file-service` | 🟡 G1/G2 | Сервис и ClamAV-контур запускаются; upload/download, чужой доступ, большие файлы и EICAR ещё не приняты в двухпользовательском сценарии |
| `notification-service` | 🟡 G1/G2 | Сервис и миграции прошли запуск; реальная цепочка Alertmanager→email→администратор ещё требует smoke |
| Наблюдаемость и эксплуатация | 🟡 Базовый стек поднят | Prometheus/Grafana/Loki описаны; доставка логов, alert rules, 72 часа, backup/restore и DR не приняты |

## 3. Что остаётся в G3

G3 нельзя закрывать только потому, что контейнеры зелёные. Оставшиеся темы выполняются
и принимаются отдельно:

1. **Собственная почта:** SMTP inbound/submission/outbound, restart safety, дедупликация,
   spam/AV/EICAR, поиск, IMAP/POP3 в Thunderbird и Outlook, папки/флаги/UID/reconnect.
2. **Webmail:** чужой доступ, вложения, HTML/изображения, длинные/кириллические имена,
   Sent/DSN и сценарии из issue #99.
3. **Identity и RBAC:** смена пароля, 2FA, app passwords, union-прав групп, защита
   последнего SUPER_ADMIN и запрет доступа к чужой почте. AD/LDAP на текущем стенде
   намеренно не настраивается.
4. **Чат:** два независимых пользователя, direct/group, реакции/read/typing/presence,
   идемпотентность `clientMessageId`, refresh/reconnect, файлы, EICAR и запрет чужого
   скачивания.
5. **External Mail:** archive migration с контрольными папками/датами/флагами/вложениями,
   pause/resume/delete, send-as и Sent, остальные провайдеры; затем безопасно настраиваемый
   polling и устойчивый IMAP IDLE с reconnect/polling fallback.
6. **Frontend:** реальный Windows 7 + Chrome 109, смартфон/планшет, RU/EN, keyboard/Escape,
   длинный Unicode-текст и отложенная ручная матрица. Legacy build сам по себе недостаточен.
7. **Эксплуатация:** мониторинг/alerting, persistent volumes после restart, backup на
   другом диске, реальное восстановление и наблюдаемость в течение 72 часов.

## 4. Ворота G0–G5

| Gate | Статус | Критерий / следующий шаг |
|---|---|---|
| **G0 — frontend и статика** | ✅ Пройден; повторяется для каждого релизного SHA | Актуальный повтор: 9/33, modern+legacy build, production audit 0 |
| **G1 — Maven** | ✅ `b37db56`; 🔁 повторить на `main` | Исторический PASS: 12 сервисов + parent, `BUILD SUCCESS`, JaCoCo не понижен; текущий Boot 3.5.16 требует нового полного прогона |
| **G2 — Docker и первый запуск** | ✅ `b37db56`; 🔁 повторить на `main` | Исторический PASS: 13 образов, Compose config, Flyway и базовый стек; поздние targeted recreate не заменяют полный повтор |
| **G3 — функциональная приёмка** | 🚧 В работе | External Mail ongoing и базовый чат проверены; закрыть раздел 3 целиком |
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
До production остаются повтор G1/G2 на текущем `main`, полный G3, отдельный G4 и контролируемый пилот G5.
