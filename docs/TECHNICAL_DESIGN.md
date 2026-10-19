# Corvex — технический проект корпоративной коммуникационной платформы

**Назначение.** Самодостаточная платформа на собственном сервере компании (до 1000
сотрудников): собственный почтовый сервер SMTP/IMAP/POP3, веб-клиент почты,
корпоративный чат реального времени, админ-панель с гибкими правами, мониторинг
с email-алертами. Единый веб-интерфейс, единая аутентификация (локальная + AD/LDAP).

**Статус документа.** Детальный технический проект + архитектурный каркас в коде:
документ не существует отдельно от репозитория — диаграммы соответствуют
`docker-compose.yml`, схемы БД — Flyway-миграциям, контракты — OpenAPI-файлам,
примеры кода — реальным файлам сервисов и собранному фронтенду.

## Состав проекта (в порядке, заданном требованиями к выводу)

| № | Раздел | Файл |
|---|---|---|
| 1 | Детальная диаграмма архитектуры (Mermaid: C4, конвейер почты, auth, чат, ADR, расчёт нагрузки) | [01-architecture.md](01-architecture.md) |
| 2 | Структура проекта (дерево, шаблон микросервиса, карта ответственности) | [02-project-structure.md](02-project-structure.md) |
| 3 | API-контракты (карта, STOMP, события RabbitMQ, internal API) + 4 OpenAPI-спецификации | [03-api-contracts.md](03-api-contracts.md), [`api/`](api/) |
| 4 | Схема БД (ER-диаграмма 4 баз, индексы Elasticsearch, квоты) | [04-database.md](04-database.md) |
| 5 | Docker-конфигурация (compose из 25 сервисов, Dockerfile, сети, тома) | [05-docker.md](05-docker.md) |
| 6 | Примеры кода ключевых компонентов | [06-backend-code.md](06-backend-code.md) |
| 7 | Frontend: компоненты, маршрутизация, состояние, Chrome 109 | [07-frontend.md](07-frontend.md) |
| 8 | Развёртывание на Windows Server (12 шагов, DNS-чек-лист, траблшутинг) | [08-deployment-windows-server.md](08-deployment-windows-server.md) |
| 9 | План разработки (7 этапов, 32 недели, MVP на 12-й, риски) | [09-development-plan.md](09-development-plan.md) |
| 10 | Тестовая стратегия (unit/integration/e2e/нагрузка/безопасность/операционные) | [10-test-strategy.md](10-test-strategy.md) |
| 11 | Анализ современного дизайна и дизайн-система Quiet Work | [11-modern-design-analysis.md](11-modern-design-analysis.md) |

## Зафиксированный стек

- **Backend:** Java 17, Spring Boot 3.2 (12 микросервисов), Spring Cloud Gateway,
  Spring Security + LDAP, SubEtha SMTP и Apache James (почтовые протоколы),
  Spring WebSocket/STOMP (чат), Flyway, JaCoCo-gate 70%;
- **Данные:** PostgreSQL 16 (4 БД по сервисам), Redis 7, RabbitMQ 3.13,
  Elasticsearch 8 (поиск), Maildir + контентно-адресуемые вложения на ФС;
- **Почтовая гигиена:** SpamAssassin, ClamAV, DKIM/SPF/DMARC, TLS везде;
- **Frontend:** React 18 + TypeScript + Vite (`plugin-legacy` → Babel под Chrome 109),
  Tailwind CSS 3, Inter Variable, Lucide, Zustand, react-router, адаптив от 320 px;
- **Инфраструктура:** Docker Compose, nginx (TLS/HSTS), Prometheus + Alertmanager +
  Grafana, Loki + Promtail, email-алерты через отдельный канал.

## Главные архитектурные решения одной строкой

Асинхронный конвейер почты (SMTP отвечает быстро, spam/AV/доставка/поиск — событиями);
fail-open спам и fail-closed-вложения антивируса; права — 24 атомарных permission,
группы суммируются; JWT RS256 на 15 минут + вращающийся refresh; Docker на Windows
Server — через Hyper-V Linux-VM (честное предупреждение вместо неподдерживаемого
Docker Desktop на сервере). Обоснования — таблица ADR в docs/01 §1.7.

## Трассировка к критериям приёмки

| Критерий | Где закрывается |
|---|---|
| Всё поднимается `docker compose up` | `docker-compose.yml`, healthcheck-зависимости; docs/05, docs/08 шаг 8 |
| Почта SMTP/IMAP/POP3 + Outlook/Thunderbird | docs/01 §1.2–1.3, порты-адаптеры James; docs/10 §10.1 (клиентская матрица) |
| Веб-почта и чат реального времени | `frontend/` (собранная витрина), STOMP-контракт docs/03 §3.3 |
| Админка и все роли §6.1 | `Permission.java`, `EffectivePermissionService`, SQL-сид групп, экран чекбоксов во фронтенде |
| Поиск < 1 с | индексы ES docs/04 §4.4, нагрузочный порог в docs/10 §10.3 |
| Алертинг email-ом | `infra/prometheus/alerts.yml` → Alertmanager → `AlertListener.java` |
| 1000 одновременных, API p95 < 500 мс | расчёт docs/01 §1.8, нагрузочные цели docs/10 §10.3 |
| Chrome 109 на Windows 7, адаптив | legacy-бандл (docs/07 §7.1), матрица docs/10 §10.4 |
| Бэкап и шифрование каналов | `scripts/backup.ps1`, nginx TLS/HSTS, обязательный TLS почтовых протоколов |

## Запуск и готовность

Подробная инструкция установки (RU/EN): [`INSTALL.md`](INSTALL.md) ·
[`INSTALL.en.md`](INSTALL.en.md). Честный статус готовности к серверу, включая
исправленные аудитом блокеры запуска и ворота перед пилотом:
[`READINESS.md`](READINESS.md).

## Что в репозитории уже код, а что — проект

Работают и проверяются локально: весь фронтенд (сборка modern+legacy бандлов),
валидность compose и всех YAML-контрактов, ключевые Java-компоненты с реальной
логикой (TOTP RFC 6238, клиенты SPAMC и clamd INSTREAM, Maildir+CAS хранилище,
STOMP-конфигурация, gateway security/routes, RBAC-каталог и сиды, потребители алертов),
все схемы БД в виде Flyway-миграций, инфраструктурные конфиги (nginx, Prometheus,
алерты, Loki, Grafana datasources), скрипты первого запуска и бэкапа.

Реализуются по этапам плана (docs/09) без изменения контрактов: полный сервисный
слой CRUD по OpenAPI, адаптеры Apache James за портами IMAP/POP3, DKIM-подписант и
relay-воркер исходящих, LDAP-синхронизатор, индексаторы Elasticsearch, виртуализация
списков фронтенда.
