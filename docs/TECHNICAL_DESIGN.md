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
| 12 | Полный аудит фронтенда, бэкенда и инфраструктуры, октябрь 2026 | [12-full-audit-2026-10.md](12-full-audit-2026-10.md) |

## Зафиксированный стек

- **Backend:** Java 17, Spring Boot 3.2 (12 микросервисов), Spring Cloud Gateway,
  Spring Security + LDAP, SubEtha SMTP и Apache James (почтовые протоколы),
  Spring WebSocket/STOMP (чат), Flyway, JaCoCo-gate 70%;
- **Данные:** PostgreSQL 16 (4 БД по сервисам), Redis 7, RabbitMQ 4.3,
  Elasticsearch 8.19 (поиск), Maildir + контентно-адресуемые вложения на ФС;
- **Почтовая гигиена:** SpamAssassin 4.0.1 (Debian 13, community Docker-образ), ClamAV 1.5.4, DKIM/SPF/DMARC, TLS везде;
- **Frontend:** React 19 + TypeScript 7 + Vite 8 (`plugin-legacy` → Babel 7 под Chrome 109),
  Tailwind CSS 3.4, Inter Variable, Lucide, Zustand 5, React Router 7, адаптив от 320 px;
- **Инфраструктура:** Docker Compose, nginx 1.30 (TLS/HSTS), Prometheus 3.13 LTS + Alertmanager +
  Grafana 13, Loki 3.7 + Promtail 3.6 как временный агент до миграции на Alloy,
  email-алерты через отдельный канал. Точные baseline-версии и исключения —
  `docs/RELEASE-DEPENDENCIES.md`.

## Главные архитектурные решения одной строкой

Асинхронный конвейер почты (SMTP отвечает быстро, spam/AV/доставка/поиск — событиями);
fail-open спам и fail-closed-вложения антивируса; права — 25 атомарных permission,
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

Stage 1 уже является кодом, а не только проектом: локальный вход, Argon2id,
lockout, вращающиеся refresh-сессии, TOTP, пароли приложений, bootstrap
SUPER_ADMIN, пользователи, группы и union прав, аудит, настройки, health и
аналитика пользователей связаны реальными auth/admin API. Фронтенд собирается в
modern+legacy варианты и полностью поддерживает RU/EN; ошибки auth/admin API
также локализуются по `Accept-Language`.

Stages 2A/2B почты также реализованы в коде: потоковый durable spool сырого
`.eml`, конвейер spam→AV→delivery с DLQ, MIME/Maildir/CAS, квоты, Elasticsearch,
webmail folders/list/detail/flags/move/send, исходящая MX-очередь с retry/DSN,
DKIM rsa-sha256 relaxed/relaxed и submission 587 с AUTH+STARTTLS. По этапам плана
остаются runtime-приёмка Stage 2 на JDK/Docker-хосте, адаптеры Apache James за
портами IMAP/POP3 (Stage 3), persistent realtime-чат и файлы (Stage 4), LDAP и
production hardening (Stage 5). Границы и остаточные риски зафиксированы в аудите
`12-full-audit-2026-10.md`; SMTP по-прежнему выключен по умолчанию до живого smoke,
чтобы принятое письмо нельзя было подтвердить и потерять.
