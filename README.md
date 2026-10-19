# Corvex — корпоративная коммуникационная платформа / Corporate Communication Platform

**[🇷🇺 Русский](#-русский)** · **[🇬🇧 English](#-english)**

---

# 🇷🇺 Русский

## Что это

**Corvex** — самодостаточная корпоративная коммуникационная платформа, которая
разворачивается на собственном сервере компании и объединяет:

- собственный почтовый сервер **SMTP / IMAP / POP3** (Outlook, Thunderbird);
- веб-клиент электронной почты;
- корпоративный чат реального времени (личные, групповые, каналы);
- админ-панель с гибкой системой прав (группы и чекбоксы из 24 привилегий);
- мониторинг с автоматическими email-алертами администратору.

Единый веб-интерфейс «почта + чат + админка», единая аутентификация
(локальные учётные записи + Active Directory/LDAP, JWT, опционально 2FA TOTP).
Целевой масштаб — до **1000 сотрудников на одном сервере**. Клиенты: Windows
7/10/11 (включая Chrome 109), Яндекс Браузер, Edge, Firefox, планшеты и смартфоны.

## Статус — честно и коротко

**Этап 1 выполнен: настоящий вход, пользователи и админка; рабочая почта — ещё нет.**
Вход (Argon2id, JWT с вращающимся refresh, TOTP 2FA), управление пользователями,
группы с 24 правами и аудит работают на реальном бэкенде; демо-режим остался явной
кнопкой витрины. Движки IMAP/POP3 и конвейер доставки писем — этапы 2–3 плана.
Боевые MX-записи на сервер пока направлять нельзя.

👉 Подробный аудит по каждому компоненту: [`docs/READINESS.md`](docs/READINESS.md)

## Быстрый старт

### Вариант 1 — только посмотреть интерфейс (2 минуты, нужен Node.js)

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

Откроется экран входа; вход с любыми данными ведёт в демо-режим (жёлтая плашка):
почта, чат и админ-панель с чекбоксами прав полностью кликабельны на учебных данных.

### Вариант 2 — весь стек на сервере (Docker)

```powershell
git clone https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex
git checkout arena/3732db07-corvex

.\scripts\first-run.ps1     # секреты, ключ JWT, пилотный TLS-сертификат, файл .env
notepad .env                # указать CORVEX_DOMAIN и CORVEX_MAIL_DOMAIN

docker compose up -d --build                 # 12 микросервисов + инфраструктура
docker compose --profile monitoring up -d    # Prometheus, Grafana, Loki, алерты
docker compose ps                            # дождаться healthy у всех сервисов
```

Открыть `https://mail.<ваш-домен>`. Первый вход — суперадминистратор `admin`;
пароль задаётся `CORVEX_BOOTSTRAP_ADMIN_PASSWORD` либо печатается один раз в лог:
`docker compose logs auth-service | Select-String BOOTSTRAP`.

📖 **Подробная инструкция** (Hyper-V/Docker Desktop/Linux, брандмауэр, DNS
MX/SPF/DKIM/DMARC, сертификаты, дымовые тесты, бэкапы, устранение проблем):
[`docs/INSTALL.md`](docs/INSTALL.md)

## Архитектура в одном абзаце

12 микросервисов на Spring Boot 3 / Java 17 за единым API-шлюзом: аутентификация
(AD + локальная, JWT RS256, TOTP), SMTP-приём/отправка на SubEtha SMTP, IMAP/POP3
на библиотеках Apache James, хранилище почты Maildir + метаданные в PostgreSQL +
дедупликация вложений по SHA-256, спам-фильтр SpamAssassin и антивирус ClamAV,
чат на WebSocket/STOMP с брокером RabbitMQ, полнотекстовый поиск на Elasticsearch,
кэш и присутствие на Redis. Входящее письмо проходит асинхронный конвейер
`SMTP → антиспам → антивирус → доставка → индексация → push`. Фронтенд —
React 18 + TypeScript, отдельная legacy-сборка для Chrome 109 (Windows 7).
Диаграммы, последовательности и обоснование каждого выбора (ADR):
[`docs/01-architecture.md`](docs/01-architecture.md).

## Документация

| Документ | Содержание |
|---|---|
| [`docs/TECHNICAL_DESIGN.md`](docs/TECHNICAL_DESIGN.md) | мастер-документ технического проекта и трассировка к требованиям |
| [`docs/READINESS.md`](docs/READINESS.md) | готовность к серверу: что работает, что нет, ворота пилота |
| [`docs/INSTALL.md`](docs/INSTALL.md) | подробная установка и эксплуатация (есть [English version](docs/INSTALL.en.md)) |
| [`docs/01…10-*.md`](docs/) | архитектура, структура проекта, API, схемы БД, Docker, код, фронтенд, Windows Server, план разработки, тестовая стратегия |
| [`docs/api/*.yaml`](docs/api/) | OpenAPI-контракты: auth, mail, chat, admin |
| [`docs/11-modern-design-analysis.md`](docs/11-modern-design-analysis.md) | анализ Gmail/Outlook/Slack/Superhuman/Linear и дизайн-система Quiet Work |

## Структура репозитория

```
services/   12 микросервисов Spring Boot (у каждого свой Dockerfile и миграции Flyway)
frontend/   React-интерфейс: вход+2FA, почта, чат, админ-панель
infra/      nginx, PostgreSQL init, Prometheus/Alertmanager, Grafana, Loki, RabbitMQ
docs/       технический проект (11 разделов) + OpenAPI + инструкции
scripts/    first-run.ps1 (инициализация), backup.ps1 (резервное копирование)
docker-compose.yml   вся платформа одной командой: 25 сервисов, 4 сети, 12 томов
```

## Порты наружу

`443` веб/API/чат · `80` редирект и Let's Encrypt · `25/587` SMTP · `993/143` IMAP ·
`995/110` POP3. Базы и очереди наружу не публикуются никогда.

## Дорожная карта

MVP веб-почты — неделя 12, IMAP/POP3 и поиск — недели 13–18, полный чат —
19–24, AD/2FA и hardening — 25–27, нагрузка и пилот — 28–32.
Подробно: [`docs/09-development-plan.md`](docs/09-development-plan.md).

---

# 🇬🇧 English

## What it is

**Corvex** is a self-hosted corporate communication platform that runs on a
company's own server and combines:

- its own mail server — **SMTP / IMAP / POP3** (Outlook, Thunderbird);
- a webmail client;
- a real-time corporate chat (DMs, group chats, channels);
- an admin panel with a flexible permission system (groups with 24 checkbox privileges);
- monitoring with automatic email alerts to administrators.

One web UI ("mail + chat + admin"), one identity system (local accounts +
Active Directory/LDAP, JWT, optional TOTP 2FA). Target scale: **up to 1,000
employees on a single server**. Clients: Windows 7/10/11 (including Chrome 109),
Yandex Browser, Edge, Firefox, tablets and smartphones.

## Status — honest and short

**Stage 1 is done: real sign-in, users and admin panel; a working mail server —
not yet.** Authentication (Argon2id, JWT with rotating refresh tokens, TOTP 2FA),
user management, groups with 24 permissions and audit run on the real backend;
demo mode remains an explicit showcase button. The IMAP/POP3 engines and the
mail delivery pipeline are roadmap stages 2–3.
Do not point production MX records at it yet.

👉 Per-component audit: [`docs/READINESS.md`](docs/READINESS.md)

## Quick start

### Option 1 — just look at the UI (2 minutes, needs Node.js)

```bash
cd frontend
npm install
npm run dev            # http://localhost:5173
```

Sign in with any credentials to enter demo mode (yellow banner): mail, chat and
the admin panel with permission checkboxes are fully clickable on sample data.

### Option 2 — full stack on a server (Docker)

```powershell
git clone https://github.com/ArtemDobrokvashin/Corvex-.git C:\Corvex
cd C:\Corvex
git checkout arena/3732db07-corvex

.\scripts\first-run.ps1     # secrets, JWT key, pilot TLS certificate, .env file
notepad .env                # set CORVEX_DOMAIN and CORVEX_MAIL_DOMAIN

docker compose up -d --build                 # 12 microservices + infrastructure
docker compose --profile monitoring up -d    # Prometheus, Grafana, Loki, alerts
docker compose ps                            # wait until everything is (healthy)
```

Open `https://mail.<your-domain>`. First sign-in is the bootstrap superadmin `admin`;
the password comes from `CORVEX_BOOTSTRAP_ADMIN_PASSWORD` or is printed once in the log:
`docker compose logs auth-service | grep BOOTSTRAP`.

📖 **Detailed guide** (Hyper-V / Docker Desktop / Linux, firewall, DNS
MX/SPF/DKIM/DMARC, certificates, smoke tests, backups, troubleshooting):
[`docs/INSTALL.en.md`](docs/INSTALL.en.md)

## Architecture in one paragraph

Twelve Spring Boot 3 / Java 17 microservices behind a single API gateway:
authentication (AD + local, JWT RS256, TOTP), SMTP send/receive on SubEtha SMTP,
IMAP/POP3 on Apache James protocol libraries, mail storage as Maildir + metadata
in PostgreSQL + SHA-256-deduplicated attachments, SpamAssassin spam filtering
and ClamAV antivirus, chat on WebSocket/STOMP brokered by RabbitMQ, full-text
search on Elasticsearch, cache and presence on Redis. Inbound mail flows through
an asynchronous pipeline `SMTP → spam → antivirus → delivery → indexing → push`.
The frontend is React 18 + TypeScript with a dedicated legacy bundle for
Chrome 109 (Windows 7). Diagrams, sequences and rationale for every choice (ADRs):
[`docs/01-architecture.md`](docs/01-architecture.md).

## Documentation

| Document | Contents |
|---|---|
| [`docs/TECHNICAL_DESIGN.md`](docs/TECHNICAL_DESIGN.md) | master technical design + traceability to requirements (RU) |
| [`docs/READINESS.md`](docs/READINESS.md) | server readiness: what works, what does not, pilot gates (RU + EN summary) |
| [`docs/INSTALL.en.md`](docs/INSTALL.en.md) | detailed installation & operations ([Russian version](docs/INSTALL.md)) |
| [`docs/01…10-*.md`](docs/) | architecture, project layout, APIs, DB schemas, Docker, code tour, frontend, Windows Server deployment, delivery plan, test strategy (RU) |
| [`docs/api/*.yaml`](docs/api/) | OpenAPI contracts: auth, mail, chat, admin |
| [`docs/11-modern-design-analysis.md`](docs/11-modern-design-analysis.md) | Gmail/Outlook/Slack/Superhuman/Linear benchmark and the Quiet Work design system (RU) |

## Repository layout

```
services/   12 Spring Boot microservices (each with its own Dockerfile & Flyway migrations)
frontend/   React UI: login + 2FA, mail, chat, admin panel
infra/      nginx, PostgreSQL init, Prometheus/Alertmanager, Grafana, Loki, RabbitMQ
docs/       technical design (11 sections) + OpenAPI + guides
scripts/    first-run.ps1 (initialisation), backup.ps1 (backups)
docker-compose.yml   the whole platform in one command: 25 services, 4 networks, 12 volumes
```

## Published ports

`443` web/API/chat · `80` redirect & Let's Encrypt · `25/587` SMTP · `993/143` IMAP ·
`995/110` POP3. Databases and queues are never published.

## Roadmap

Webmail MVP — week 12; IMAP/POP3 and search — weeks 13–18; full chat — 19–24;
AD/2FA and hardening — 25–27; load testing and pilot — 28–32.
Details: [`docs/09-development-plan.md`](docs/09-development-plan.md).
