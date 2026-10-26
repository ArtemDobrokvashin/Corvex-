# 5. Docker-конфигурация

> К пункту 5 формата вывода. Источник истины — [`/docker-compose.yml`](../docker-compose.yml)
> (25 сервисов) и Dockerfile каждого микросервиса; здесь — как это устроено и почему.

## 5.1. Состав compose

| Группа | Сервисы | Образы |
|---|---|---|
| Данные | postgres, redis, rabbitmq, elasticsearch | официальные: `postgres:16-alpine`, `redis:7-alpine`, `rabbitmq:4.3.6-management-alpine`, ES `8.19.23` |
| Фильтры | spamd, clamav | `grepular/spamassassin:4.0.1-5` (community-образ SpamAssassin 4.0.1 из Debian 13; официального Apache Docker-образа нет), `clamav/clamav:1.5.4` (freshclam внутри) |
| Corvex | 12 микросервисов + frontend | собираются локально из `services/*` и `frontend/` |
| Периметр | nginx | `nginx:1.30-alpine`, конфиг в `infra/nginx` |
| Мониторинг (профиль `monitoring`) | prometheus, alertmanager, grafana, loki, promtail | Prometheus `v3.13.4`, Alertmanager `v0.34.1`, Grafana `13.2.4`, Loki `3.7.8`; Promtail `3.6.11` — только временный optional smoke перед миграцией на Alloy (см. `docs/RELEASE-DEPENDENCIES.md`) |

## 5.2. Dockerfile микросервиса (одинаковый паттерн у всех 12)

- стадия build: `maven:3.9-eclipse-temurin-17`, `dependency:go-offline` отдельным слоем
  (кэш зависимостей переживает изменения кода);
- стадия runtime: `eclipse-temurin:17-jre-alpine`, пользователь `corvex` (non-root,
  фиксированный `UID:GID = 100:101`; `first-run.ps1` под этот аккаунт нормализует права
  на сгенерированные ключи), поэтому почтовые порты внутри контейнера непривилегированные
  (2525/2587/1143/…), наружу Docker публикует стандартные 25/587/143/993/110/995;
- `JAVA_OPTS=-XX:MaxRAMPercentage=75` — heap считается от лимита контейнера, а не хоста;
- `HEALTHCHECK` по `/actuator/health` — на нём строятся `depends_on: condition:
  service_healthy`, поэтому `docker compose up` стартует в правильном порядке
  (сначала БД/очереди, потом сервисы, потом gateway и nginx).

## 5.3. Сети (сегментация по docs/01 §1.6)

- `edge` — nginx, gateway, frontend, grafana; единственная «наружу» (кроме почтовых портов);
- `app` — все микросервисы и фильтры между собой;
- `data` (`internal: true`) — postgres/redis/rabbitmq/elasticsearch: даже маршрутов
  наружу нет, порты на хост не публикуются;
- `monitoring` — prometheus/alertmanager/grafana/loki/promtail.

## 5.4. Тома и что бэкапится

| Том | Содержимое | В бэкап |
|---|---|---|
| `postgres-data` | 4 БД | да, pg_dump (scripts/backup.ps1) |
| `mail-data` | Maildir всех ящиков | **да, критично** |
| `attachment-data`, `chat-file-data` | вложения CAS, файлы чата | да |
| `rabbitmq-data`, `redis-data` | очереди, сессии/presence | нет (восстанавливаемо/эфемерно) |
| `elasticsearch-data` | поисковый индекс | нет (переиндексация из БД) |
| `clamav-data`, `spamd-data` | сигнатуры, обученные правила Bayes | желательно (экономит время первого старта) |
| `prometheus-data`, `loki-data` | метрики 30 дн., логи 31 дн. | нет |

## 5.5. Команды эксплуатации

```powershell
docker compose up -d --build                 # первый запуск всей системы
docker compose --profile monitoring up -d      # до-включить мониторинг
docker compose ps                              # здоровье всех сервисов
docker compose logs -f mail-smtp-service       # логи одного сервиса (или Loki в Grafana)
docker compose pull && docker compose up -d    # обновление базовых образов
docker compose up -d --build chat-service     # пересборка одного микросервиса (остальные не трогаются)
```

Обновление Corvex: образы версионируются тегами вида `corvex/<сервис>:1.2.0`,
откат = вернуть тег в compose и `up -d`; миграции Flyway только аддитивные
(expand-contract), чтобы откат образа не требовал отката схемы.
