# Release dependencies и ворота Corvex

_Последняя сверка dependency-версий и повторный frontend-прогон: 19 октября 2026 года, `main` = `ad42ff2`._

Этот документ фиксирует версии в текущих файлах `main`. Они взяты из POM,
`package-lock.json`, Dockerfile и `docker-compose.yml`, а не из предполагаемого будущего
upgrade. Исторические runtime-результаты не переносятся на новые версии автоматически;
границы приёмки — в [`READINESS.md`](READINESS.md).

## 1. Правила обновления

1. **Сначала исправления и зелёные ворота, затем новый baseline.** Не совмещать major-upgrade
   с нестабильной сборкой, сменой архитектуры и большим количеством независимых правок.
2. Patch/minor обновления проходят релевантные тесты. Major Spring/James/JDK/frontend —
   отдельным изменением с повторением G0–G3.
3. Не понижать JaCoCo и не отключать тесты ради зелёного результата. Не делать массовый
   downgrade Spring/James по первой ошибке: сначала проверить точную сигнатуру API и
   первый `Caused by`.
4. `npm ci` обязателен для воспроизводимого frontend-графа. Docker build не заменяет
   `mvn verify`: сервисные Dockerfile собирают артефакты с `-DskipTests`.
5. Каждый отчёт содержит точный SHA, версии JDK/Maven/Node/Docker и первую ошибку.

## 2. Текущий backend baseline

| Компонент | Зафиксировано сейчас | Комментарий |
|---|---|---|
| JDK | **17** | Все 12 сервисов используют `<java.version>17</java.version>`; Docker runtime — `eclipse-temurin:17-jre-alpine` |
| Maven build image | `maven:3.9-eclipse-temurin-17` | На `ad42ff2` затронутые модули (`admin`, `mail-storage`, `notification`, `web-gateway`) дают `BUILD SUCCESS` с тестами; полный PC reactor последний раз был зелёным на `8cccaee` и повторяется для каждого release SHA |
| Spring Boot parent | **3.5.16** | Одинаковая версия во всех 12 service POM |
| Spring Cloud BOM, gateway | **2025.0.3** | Используется `web-gateway` с Boot 3.5 |
| Spring Cloud BOM, остальные POM | **2023.0.6** | Остаточный неоднородный dependency-management baseline; унификация относится к G4, не выдаётся за завершённую |
| Apache James protocols | **3.8.2** | IMAP/POP3; Java 17 сохраняется до отдельной миграции G4 |
| SubEtha SMTP | `com.github.subethasmtp:subethasmtp:7.2.2` | Поддерживаемый форк вместо заброшенной линии 3.1.7 |
| dnsjava | **3.7.1** | MX/DNS-резолвинг SMTP; 3.7.x закрывает GHSA 2026 (DoS на разборе заголовков, TSIG и DoH) |
| Jakarta Mail API / Angus Mail | **2.1.5 / 2.0.5** | External Mail IMAP/POP3/SMTP; точные сигнатуры reconnect/fetch проверялись по исходникам |
| JaCoCo | **0.8.16** | Coverage-гейты не понижались |
| BouncyCastle (`bcprov-jdk18on`) | **1.87** | Argon2id для паролей и app-password (`Argon2PasswordEncoder` Spring Security) |

**Граница доказательств:** на `ad42ff2` с Boot 3.5.16, gateway Cloud 2025.0.3 и
James 3.8.2 затронутые модули собираются с тестами, а `docker compose up -d --build`
поднимает 16/16 build-контекстов на существующих volumes. Полный reactor по 12 сервисам
последний раз был зелёным на `8cccaee`; clean-volume install не выполнялся ни разу.
`d75fb97` покрыт targeted POP3-прогоном и клиентским smoke, а не полным reactor:
G1/G2 повторяются для каждого release SHA.
Смешанные Cloud BOM, James 3.8.2 и Java 17 также показывают, почему нужен G4.

### Запланированный G4

G4 — не косметический bump, а отдельная поддерживаемая платформа: согласованные Spring
Boot/Cloud/Jackson, James и JDK, обновление observability-стека (включая замену Promtail
на Alloy), затем полный повтор Maven, Docker, webmail, SMTP/IMAP/POP3 и chat-smoke.
Точные целевые версии выбираются на момент G4 по официально поддерживаемым линиям и их
совместимости; документ не объявляет непроверенный upgrade уже выполненным.

## 3. Текущий frontend baseline

Воспроизводимый граф определяется `frontend/package-lock.json`. Основные resolved-версии:

| Пакет / инструмент | Версия |
|---|---|
| Node.js engines | `^22.22.2 || ^24.15.0 || >=26.0.0`; npm `>=10` |
| React / React DOM | **19.3.1 / 19.3.1** |
| React Router DOM | **7.18.5** |
| TypeScript / Vite / Vitest | **7.0.2 / 8.3.5 / 5.0.5** |
| Autoprefixer / PostCSS | **10.6.2 / 8.5.32** |
| jsdom | **30.1.3** |
| Tailwind CSS | **3.4.19** |
| `@vitejs/plugin-legacy` / `@babel/core` | **8.2.3 / 7.29.7** |
| Zustand | **5.1.0** |
| Lucide React | **1.72.0** (lockfile; переход на 2.x — отдельная major-миграция) |
| Marked / DOMPurify | **18.2.0 / 3.4.17** |

Повторная проверка обновления PR #106 02.11.2026 на Node 22.22.3 / npm 10.9.8:

19.10.2026 на `main` = `ad42ff2`:

- `npm ci` — успешно (364 пакета);
- `npm test` — **67 test-файлов, 310/310 тестов**;
- `npm run build` — `tsc -b`, modern и `*-legacy-*.js` bundles успешно;
- `npm audit --omit=dev` — **0 уязвимостей**.

Повторная проверка 16.11.2026 после слияния Dependabot-батча (Lucide React 1.72.0,
Marked 18.2.0) на `main` = `d865bf7f`:

- `npm ci` — успешно (364 пакета);
- `tsc -b` — 0 ошибок; `npm test` — **71 test-файл, 356/356 тестов**;
- `npm run build` — modern и `*-legacy-*.js` bundles собраны (40 файлов в `dist/assets`);
- `npm audit --omit=dev` — **0 уязвимостей**;
- `python3 -m unittest discover -s scripts/tests` — **247 OK (1 skip)**.

### Осознанные исключения

- **Tailwind 4:** не применять без отдельного доказательства Chrome 109. Tailwind 4
  использует более новые браузерные возможности; текущая обязательная цель — Chrome 109
  на Windows 7. Это осознанное ограничение, а не разрешение игнорировать security-находки.
- **Babel 8:** legacy-цепочка остаётся на Babel 7 до отдельной проверенной миграции.
- **Major frontend-обновления:** например, переход Lucide на 2.x рассматривать по
  changelog и smoke-результатам, а не объединять с независимой функциональной работой.

Production audit равен нулю на текущем lockfile. Это не утверждение, что весь dev-граф
или любой будущий lockfile вечно чист: audit повторяется после каждого обновления.

## 4. Docker-инфраструктура

| Компонент | Текущий image/tag |
|---|---|
| PostgreSQL | `postgres:16-alpine` |
| Redis | `redis:7-alpine` |
| RabbitMQ | `rabbitmq:4.3.7-management-alpine` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:8.19.25` |
| SpamAssassin | `grepular/spamassassin:4.0.1-5` |
| ClamAV | `clamav/clamav:1.5.4` |
| nginx | `nginx:1.31-alpine` |
| Prometheus | `prom/prometheus:v3.16.0` |
| Alertmanager | `prom/alertmanager:v0.35.0` |
| External alert relay | `python:3.13-alpine`; только стандартная библиотека, non-root UID 10001 |
| Grafana | `grafana/grafana:13.3.1` |
| Loki | `grafana/loki:3.7.9` |
| Promtail | `grafana/promtail:3.6.11` — до миграции на Alloy в G4 |
| Frontend build | `node:22-alpine` → `nginx:1.31-alpine` |
| Java services | `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre-alpine` |

RabbitMQ **4.4.0 намеренно не включён** в этот staged-update. Прямой переход с 4.3.6
в образе 4.4.0 одновременно меняет RabbitMQ и Erlang/OTP 27→29; официальные release notes
для такого случая рекомендуют сначала 4.3.7+. Кроме того, новые classic-queue v2-файлы
4.4 нельзя откатить на 4.3.x с тем же volume. Поэтому сейчас зафиксирован 4.3.7, а 4.4
требует отдельного изменения после backup, проверки очередей/DLQ/restart и rollback-плана.
Dependabot minor-update для RabbitMQ до этого приостановлен; patch-обновления разрешены.

Для Alertmanager 0.35.0 и Grafana 13.3.1 перед merge проверены объявленные breaking changes:
0.35.0 ломает `--enable-feature=auto-gomemlimit` и неименованные outputs `eventrecorder`,
а также меняет схему `events/v2` — ни то, ни другое Corvex не использует: entrypoint вызывает
только `--config.file`/`--storage.path`, конфиг ограничен `route`/`receivers`/`webhook_configs`/
`inhibit_rules`. Prometheus 3.16.0 меняет экспериментальный search API, remote-write метрики и
TSDB-валидацию exemplars; scrape-конфиг, `--storage.tsdb.retention.time` и alerting-правила
не затронуты. Compose YAML и monitoring-профили проверены статически; запуск
новых monitoring-образов и alert/email-smoke остаются обязательной частью повторных G2/G3.

**Граница доказательств для батча 2026-11 (dnsjava 3.7.1, bcprov-jdk18on 1.87, JaCoCo 0.8.16):**
используемые Corvex API (`Lookup`, `SimpleResolver`, `ResolverConfig.server()/servers()`,
`TXTRecord.getStrings()`, `Cache`, `Header.setFlag`, `Lookup.setCache(null)` → временный кэш)
сверены по исходникам тега v3.7.1; bcprov и JaCoCo обновлены в пределах patch/minor без изменения
нашего кода. Компиляция и Maven-тесты для этого SHA не выполнялись: полный `mvn verify` и
Docker-сборка остаются обязательной проверкой на PC.

Теги — не digest-пины. Для production G5 нужно отдельно решить политику digest/immutable
artifacts, сканирование образов и обновление базовых ОС. SpamAssassin использует
community-образ, потому что официального Apache image нет; это нужно учитывать при
supply-chain review.

## 5. Dependabot без потока мелких PR

`.github/dependabot.yml` настроен на еженедельные **сгруппированные minor/patch** updates
по понедельникам (Europe/Moscow): npm, Maven, Docker base images и Compose images.
Major-версии автоматически не предлагаются. Для каждого ecosystem действует
`open-pull-requests-limit: 1`; James minor/major заблокированы до согласованной JDK/James
миграции, RabbitMQ minor — до ручного staged-upgrade с backup/rollback smoke. Spring Cloud
minor release trains отложены до G4, а automatic Docker updates тега `maven` отключены:
один такой тег связывает Maven и JDK, поэтому смену JDK нельзя считать обычным patch.
Это намеренно предотвращает десятки однотипных PR по 12 каталогам сервисов и небезопасные
coupled upgrades.

Открытые Dependabot PR не следует смешивать с функциональным или документационным PR.

## 6. Ворота и фактический статус

### G0 — frontend и статические проверки: ✅ пройдено, повторять на релизном SHA

Минимум: `npm ci`, tests, TypeScript/modern/legacy build, production audit, YAML/XML/JSON,
локальные Markdown-ссылки и `git diff --check`. Реальный Chrome 109 остаётся отдельным
ручным G3-тестом — наличие legacy bundle его не заменяет.

### G1 — Maven на JDK-хосте: 🟢 затронутые модули на `ad42ff2`, 🔁 полный reactor

```bash
mvn -B clean verify
```

На `ad42ff2` (Boot 3.5.16) `admin-service`, `mail-storage-service`,
`notification-service` и `web-gateway` дают `BUILD SUCCESS` с тестами модулей.
Полный reactor по 12 сервисам и parent в последний раз был зелёным на `8cccaee`;
для текущего SHA он остаётся обязательным шагом, и targeted test не выдаётся за
полный прогон.

### G2 — Docker и первый запуск: ✅ на существующих volumes (`ad42ff2`), ⏳ clean-volume

```bash
docker compose config -q
docker compose build
docker compose up -d
docker compose ps
```

На `ad42ff2` `docker compose up -d --build` поднял **16 из 16** build-контекстов,
контейнеры перешли в `Started`/`healthy`, миграции `V6`/`V8` применились.
Установка на пустых volumes (`down -v` только на пустом стенде) не выполнялась и
остаётся отдельным шагом. Для нового стенда сначала выполняется
`scripts/first-run.ps1` (Windows) либо эквивалент из `docs/INSTALL.md`.

### G3 — функциональная приёмка: 🚧 в работе

Подтверждены ограниченный External Mail ongoing, базовый realtime chat, файлы чата,
скачивание вложений webmail и согласованный POP3 995 smoke. Серверный календарь
(`ad42ff2`) собран и ждёт живой приёмки. Остаются собственный сквозной SMTP, IMAP и
почтовые клиенты, полный двухпользовательский чат/файлы, calendar live, archive
migration, backup/restore, мониторинг/alerting, реальный Chrome 109 и остальные
сценарии из `READINESS.md`.

### G4 — поддерживаемая платформа: ⏳ впереди

Только отдельным изменением после стабильного baseline. Обязателен повтор G1–G3 без
сокращения тестов и без скрытого понижения качества.

### G5 — пилот / production: ⏳ впереди

Нагрузка и 72 часа наблюдения, security hardening, AD/LDAP при необходимости,
backup/restore/DR, ограниченный пилот и план отката. До этого production MX, рабочие
ящики и публичный SMTP не разрешаются.

Полная PC-инструкция: [`TESTING-ON-YOUR-PC.md`](TESTING-ON-YOUR-PC.md).
