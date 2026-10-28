# Release dependencies и ворота Corvex

_Последняя сверка с репозиторием и повторный frontend-прогон: 28 октября 2026 года._

Этот документ фиксирует фактический baseline `main` после PR #95/#96. Версии ниже взяты
из POM, `package-lock.json`, Dockerfile и `docker-compose.yml`, а не из предполагаемого
будущего upgrade. Статус runtime-приёмки — в [`READINESS.md`](READINESS.md).

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
| Maven build image | `maven:3.9-eclipse-temurin-17` | Полный PC reactor на `b37db56` проверен Maven 3.10.0 / Java 17.0.20.1; на текущем Boot 3.5.16 повторить |
| Spring Boot parent | **3.5.16** | Одинаковая версия во всех 12 service POM |
| Spring Cloud BOM, gateway | **2025.0.3** | Используется `web-gateway` с Boot 3.5 |
| Spring Cloud BOM, остальные POM | **2023.0.6** | Остаточный неоднородный dependency-management baseline; унификация относится к G4, не выдаётся за завершённую |
| Apache James protocols | **3.8.2** | IMAP/POP3; Java 17 сохраняется до отдельной миграции G4 |
| SubEtha SMTP | `com.github.subethasmtp:subethasmtp:7.2.2` | Поддерживаемый форк вместо заброшенной линии 3.1.7 |
| dnsjava | **3.6.5** | MX/DNS-резолвинг SMTP |
| Jakarta Mail API / Angus Mail | **2.1.5 / 2.0.5** | External Mail IMAP/POP3/SMTP; точные сигнатуры reconnect/fetch проверялись по исходникам |
| JaCoCo | **0.8.15** | Coverage-гейты не понижались |

**Граница доказательств:** полный PC-pass G1/G2 получен на `b37db56` с Boot 3.2.12,
Cloud 2023.0.6 и James 3.8.2. PR #95 уже содержал Boot 3.5.16 при merge; текущий
`main` использует Boot 3.5.16 и gateway Cloud 2025.0.3. Старый PASS нельзя задним
числом приписать новой версии: G1/G2 на текущем `main` остаются обязательным повтором.
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
| React Router DOM | **7.18.4** |
| TypeScript / Vite / Vitest | **7.0.2 / 8.3.4 / 5.0.5** |
| jsdom | **30.1.3** |
| Tailwind CSS | **3.4.19** |
| `@vitejs/plugin-legacy` / `@babel/core` | **8.2.3 / 7.29.7** |
| Zustand | **5.0.15** |
| Lucide React | **1.60.0** (lockfile; переход на 2.x — отдельная major-миграция) |
| Marked / DOMPurify | **18.1.1 / 3.4.17** |

Повторная проверка 28.10.2026 на Node 22.22.3 / npm 10.9.8:

- `npm ci` — успешно;
- `npm test` — **9 test-файлов, 33/33 теста**;
- `npm run build` — TypeScript, modern и `*-legacy-*.js` bundles успешно;
- `npm audit --omit=dev --audit-level=moderate` — **0 уязвимостей**.

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
| RabbitMQ | `rabbitmq:4.3.6-management-alpine` |
| Elasticsearch | `docker.elastic.co/elasticsearch/elasticsearch:8.19.23` |
| SpamAssassin | `grepular/spamassassin:4.0.1-5` |
| ClamAV | `clamav/clamav:1.5.4` |
| nginx | `nginx:1.31-alpine` |
| Prometheus | `prom/prometheus:v3.15.0` |
| Alertmanager | `prom/alertmanager:v0.34.1` |
| Grafana | `grafana/grafana:13.2.4` |
| Loki | `grafana/loki:3.7.8` |
| Promtail | `grafana/promtail:3.6.11` — до миграции на Alloy в G4 |
| Frontend build | `node:22-alpine` → `nginx:1.31-alpine` |
| Java services | `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre-alpine` |

Теги — не digest-пины. Для production G5 нужно отдельно решить политику digest/immutable
artifacts, сканирование образов и обновление базовых ОС. SpamAssassin использует
community-образ, потому что официального Apache image нет; это нужно учитывать при
supply-chain review.

## 5. Dependabot без потока мелких PR

`.github/dependabot.yml` настроен на еженедельные **сгруппированные minor/patch** updates
по понедельникам (Europe/Moscow): npm, Maven, Docker base images и Compose images.
Major-версии автоматически не предлагаются. Для каждого ecosystem действует
`open-pull-requests-limit: 1`; James minor/major заблокированы до согласованной JDK/James
миграции. Это намеренно предотвращает десятки однотипных PR по 12 каталогам сервисов.

Открытые Dependabot PR не следует смешивать с функциональным или документационным PR.

## 6. Ворота и фактический статус

### G0 — frontend и статические проверки: ✅ пройдено, повторять на релизном SHA

Минимум: `npm ci`, tests, TypeScript/modern/legacy build, production audit, YAML/XML/JSON,
локальные Markdown-ссылки и `git diff --check`. Реальный Chrome 109 остаётся отдельным
ручным G3-тестом — наличие legacy bundle его не заменяет.

### G1 — Maven на JDK-хосте: ✅ `b37db56`; 🔁 повторить на текущем `main`

```bash
mvn -B clean verify
```

На `b37db56` с Boot 3.2.12 получен `BUILD SUCCESS` для 12 сервисов и parent.
PR #95 уже содержал Boot 3.5.16 при merge, поэтому для текущего `main` нужен новый
полный reactor; targeted test не выдаётся за полный прогон.

### G2 — Docker и первый запуск: ✅ `b37db56`; 🔁 повторить на текущем `main`

```bash
docker compose config -q
docker compose build
docker compose up -d
docker compose ps
```

На `b37db56` собраны 13 образов, применены Flyway-миграции и поднят базовый стек.
Поздние targeted gateway/chat-пересборки не являются полным G2 текущего `main`.
После смены Boot нужно повторить запуск. Для нового стенда сначала выполняется
`scripts/first-run.ps1` (Windows) либо эквивалент из `docs/INSTALL.md`.

### G3 — функциональная приёмка: 🚧 в работе

Подтверждены ограниченный External Mail ongoing и базовый realtime chat. Остаются
собственные SMTP/IMAP/POP3 и почтовые клиенты, полный двухпользовательский чат/файлы,
webmail/вложения, archive migration, backup/restore, мониторинг/alerting, реальный
Chrome 109 и остальные сценарии из `READINESS.md`.

### G4 — поддерживаемая платформа: ⏳ впереди

Только отдельным изменением после стабильного baseline. Обязателен повтор G1–G3 без
сокращения тестов и без скрытого понижения качества.

### G5 — пилот / production: ⏳ впереди

Нагрузка и 72 часа наблюдения, security hardening, AD/LDAP при необходимости,
backup/restore/DR, ограниченный пилот и план отката. До этого production MX, рабочие
ящики и публичный SMTP не разрешаются.

Полная PC-инструкция: [`TESTING-ON-YOUR-PC.md`](TESTING-ON-YOUR-PC.md).
