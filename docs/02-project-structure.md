# 2. Структура проекта

> К пункту 2 формата вывода. Монорепозиторий: каждый микросервис — самостоятельный
> Maven-проект со своим Dockerfile (собирается и версионируется независимо),
> единый `docker-compose.yml` поднимает всю платформу.

## 2.1. Дерево репозитория (сокращённо, без рутины каждого сервиса)

```
Corvex-/
├── docker-compose.yml              # вся система: 25 сервисов, 4 сети, 12 томов
├── .env.example                    # образец конфигурации (секреты, домены, LDAP)
├── README.md                       # быстрый старт
├── docs/
│   ├── TECHNICAL_DESIGN.md         # мастер-документ: сводка + трассировка к ТЗ
│   ├── 01-architecture.md          # диаграммы, последовательности, ADR
│   ├── 02-project-structure.md     # (этот файл)
│   ├── 03-api-contracts.md         # карта API, события RabbitMQ, STOMP
│   ├── 04-database.md              # ER-модель и индексы ES
│   ├── 05-docker.md                # образы, сети, тома, профили
│   ├── 06-backend-code.md          # карта ключевых примеров кода
│   ├── 07-frontend.md              # структура React-приложения
│   ├── 08-deployment-windows-server.md
│   ├── 09-development-plan.md
│   ├── 10-test-strategy.md
│   └── api/                        # OpenAPI: auth-api, mail-api, chat-api, admin-api (.yaml)
├── scripts/
│   ├── first-run.ps1               # секреты, ключ JWT, пилотный сертификат
│   └── backup.ps1                  # pg_dump + снапшоты томов (Task Scheduler)
├── infra/
│   ├── nginx/                      # reverse proxy, TLS, HSTS, проброс /api и /ws
│   ├── postgres/init/              # создание четырёх БД
│   ├── prometheus/                 # scrape-конфиг, правила алертов, Alertmanager
│   ├── grafana/provisioning/       # датасорсы Prometheus+Loki
│   ├── loki/                       # Loki + Promtail (сбор логов Docker)
│   ├── certs/                      # TLS-сертификаты (не в git)
│   └── secrets/                    # приватный ключ JWT (не в git)
├── frontend/                       # React 19 + TypeScript 7 + Vite 8 (подробно — docs/07)
└── services/                       # 12 микросервисов Spring Boot 3.2 / Java 17
```

## 2.2. Шаблон одного микросервиса

Все 12 сервисов устроены одинаково (пример — `mail-storage-service`):

```
services/mail-storage-service/
├── pom.xml                         # spring-boot-starter-parent 3.2.5, JaCoCo gate 70%
├── Dockerfile                      # multi-stage: maven build → eclipse-temurin:17-jre-alpine, non-root
└── src/
    ├── main/
    │   ├── java/ru/corvex/mailstorage/
    │   │   ├── MailStorageApplication.java
    │   │   ├── domain/             # JPA-сущности (MessageMetadata, MailFolder)
    │   │   └── storage/            # MaildirStorage (Maildir + CAS)
    │   │   # по мере роста: api/ (REST), service/, repository/, messaging/ (RabbitMQ), config/
    │   └── resources/
    │       ├── application.yml     # конфиг только через env-переменные (12-factor)
    │       └── db/migration/       # Flyway V1__*.sql — своя БД, свои миграции
    └── test/java/ru/corvex/mailstorage/
```

Соглашения внутри сервисов:

- пакеты по слоям: `api` (контроллеры/DTO) → `service` (бизнес-логика, транзакции) →
  `domain`/`repository`; контроллеры не трогают репозитории напрямую;
- внешние системы — через интерфейсы-порты (`ImapServerPort`, `RawMessageStoreClient`),
  это даёт фейки в unit-тестах и заменяемость библиотек;
- каждый сервис: `/actuator/health` (liveness/readiness), `/actuator/prometheus`,
  структурные логи в stdout с `requestId` из gateway;
- версионирование API — `/api/v1`, ломающие изменения — только новой мажорной версией.

## 2.3. Карта сервисов и их зоны ответственности

| Сервис | Порт(ы) | Своя БД | Ключевая ответственность |
|---|---|---|---|
| web-gateway | 8080 | — | маршруты, JWT-проверка, rate limit, CORS, circuit breaker |
| auth-service | 8081 | auth_db | вход (локальный+AD), JWT/JWKS, refresh, TOTP, app-passwords |
| mail-smtp-service | 8082, 25/587 | — | приём и отправка SMTP, DKIM-подпись, очередь исходящих |
| mail-imap-service | 8083, 143/993 | — | IMAP для Outlook/Thunderbird поверх storage API |
| mail-pop3-service | 8084, 110/995 | — | POP3 для устаревших клиентов |
| mail-storage-service | 8085 | mail_db | папки, письма, Maildir+CAS, правила, квоты, индексация в ES |
| mail-spam-service | 8086 | — | скоринг SpamAssassin, обучение, fail-open политика |
| mail-antivirus-service | 8087 | — | сканирование ClamAV, блокировка инфицированных вложений |
| chat-service | 8088 | chat_db | комнаты, сообщения, треды, реакции, STOMP, presence |
| chat-file-service | 8089 | — (ФС) | загрузка/раздача файлов чата, превью, AV перед публикацией |
| admin-service | 8090 | admin_db | пользователи* , группы и права, настройки, аудит, аналитика |
| notification-service | 8091 | — | email-алерты Alertmanager и служебные письма |

\* Физически учётные записи хранит auth_db; admin-service управляет ими через internal
API auth-service — единая точка владения identity, админка не пишет в чужую БД.
