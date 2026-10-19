# Corvex — корпоративная коммуникационная платформа

Собственный почтовый сервер (SMTP/IMAP/POP3), веб-почта, корпоративный чат
реального времени, админ-панель с гибкой системой прав и мониторинг — как единая
система на одном сервере компании, до 1000 сотрудников. Windows Server на хосте,
клиенты — Windows 7/10/11 (включая Chrome 109), Outlook и Thunderbird.

📘 **Полный технический проект:** [`docs/TECHNICAL_DESIGN.md`](docs/TECHNICAL_DESIGN.md)
(архитектурные диаграммы, API-контракты OpenAPI, схемы БД, Docker, примеры кода,
фронтенд, развёртывание, план разработки, тестовая стратегия).

## Быстрый старт

```powershell
git clone <репозиторий> C:\Corvex; cd C:\Corvex
Copy-Item .env.example .env        # заполнить домены и секреты
.\scripts\first-run.ps1             # ключ JWT, пилотный сертификат, пароли
docker compose up -d --build       # вся система: почта, чат, веб, админка
docker compose --profile monitoring up -d   # Prometheus/Grafana/Loki/алерты
```

Открыть `https://mail.<ваш-домен>` → первый вход суперадминистратора
(одноразовый пароль — в логе `auth-service`, см. инструкцию шаг 9).

Подробная пошаговая инструкция, включая DNS (MX/SPF/DKIM/DMARC), сертификаты и
нюанс Docker на Windows Server: [`docs/08-deployment-windows-server.md`](docs/08-deployment-windows-server.md).

## Только интерфейс (витрина без бэкенда)

```bash
cd frontend && npm install && npm run dev   # http://localhost:5173
```

Почта, чат и админка полностью кликабельны на помеченных демо-данных; при поднятом
бэкенде тот же интерфейс автоматически работает через реальный API.

## Структура

- `services/` — 12 микросервисов Spring Boot 3 / Java 17 (auth, SMTP, IMAP, POP3,
  storage, spam, antivirus, chat, chat-file, gateway, admin, notification);
- `frontend/` — React 18 + TypeScript, сборка с legacy-бандлом для Chrome 109;
- `infra/` — nginx, PostgreSQL init, Prometheus/Alertmanager, Grafana, Loki;
- `docs/` — технический проект из 10 разделов + OpenAPI-спецификации в `docs/api/`;
- `scripts/` — первичная инициализация и резервное копирование (PowerShell).

## Статус

Архитектура и контракты зафиксированы; репозиторий содержит каркас всех сервисов
с ключевыми компонентами (TOTP, клиенты SpamAssassin/ClamAV, Maildir-хранилище,
STOMP-чат, RBAC), полные схемы БД и собранный фронтенд. Поэтапная реализация до
продакшена — [`docs/09-development-plan.md`](docs/09-development-plan.md) (MVP — 12 недель).
